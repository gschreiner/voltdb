/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb;

import org.voltdb.VoltDB.Configuration;
import org.voltdb.client.ProcCallException;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.utils.MiscUtils;

public class TestAdhocAlterTable extends AdhocDDLTestBase {

    public void testAlterAddColumn() throws Exception
    {
        String pathToCatalog = Configuration.getPathToCatalogForTest("adhocddl.jar");
        String pathToDeployment = Configuration.getPathToCatalogForTest("adhocddl.xml");

        VoltProjectBuilder builder = new VoltProjectBuilder();
        builder.addLiteralSchema(
                "create table FOO (" +
                "ID integer not null," +
                "VAL bigint, " +
                "constraint pk_tree primary key (ID)" +
                ");\n"
                );
        builder.addPartitionInfo("FOO", "ID");
        boolean success = builder.compile(pathToCatalog, 2, 1, 0);
        assertTrue("Schema compilation failed", success);
        MiscUtils.copyFile(builder.getPathToDeployment(), pathToDeployment);

        VoltDB.Configuration config = new VoltDB.Configuration();
        config.m_pathToCatalog = pathToCatalog;
        config.m_pathToDeployment = pathToDeployment;

        try {
            startSystem(config);

            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO add column NEWCOL varchar(50);");
            }
            catch (ProcCallException pce) {
                fail("Alter table to add column should have succeeded");
            }
            assertTrue(verifyTableColumnType("FOO", "NEWCOL", "VARCHAR"));
            assertTrue(verifyTableColumnSize("FOO", "NEWCOL", 50));
            assertTrue(isColumnNullable("FOO", "NEWCOL"));

            // second time should fail
            boolean threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO add column NEWCOL varchar(50);");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            assertTrue("Adding the same column twice should fail", threw);

            // can't add another primary key
            threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO add column BADPK integer primary key;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            assertTrue("Shouldn't be able to add a second primary key", threw);

            // Can't add a not-null column with no default
            threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO add column BADNOTNULL integer not null;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            assertTrue("Shouldn't be able to add a not null column without default", threw);

            // but we're good with a default
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO add column GOODNOTNULL integer default 0 not null;");
            }
            catch (ProcCallException pce) {
                fail("Should be able to add a column with not null and a default.");
            }
            assertTrue(verifyTableColumnType("FOO", "GOODNOTNULL", "INTEGER"));
            assertFalse(isColumnNullable("FOO", "GOODNOTNULL"));
        }
        finally {
            teardownSystem();
        }
    }

    public void testAlterDropColumn() throws Exception
    {
        String pathToCatalog = Configuration.getPathToCatalogForTest("adhocddl.jar");
        String pathToDeployment = Configuration.getPathToCatalogForTest("adhocddl.xml");

        VoltProjectBuilder builder = new VoltProjectBuilder();
        builder.addLiteralSchema(
                "create table FOO (" +
                "PKCOL integer not null," +
                "DROPME bigint, " +
                "PROCCOL bigint, " +
                "VIEWCOL bigint, " +
                "INDEXCOL bigint, " +
                "INDEX1COL bigint, " +
                "INDEX2COL bigint, " +
                "constraint pk_tree primary key (PKCOL)" +
                ");\n" +
                "create procedure BAR as select PROCCOL from FOO;\n" +
                "create view FOOVIEW (VIEWCOL, TOTAL) as select VIEWCOL, COUNT(*) from FOO group by VIEWCOL;\n" +
                "create index FOODEX on FOO(INDEXCOL);\n" +
                "create index FOO2DEX on FOO(INDEX1COL, INDEX2COL);\n" +
                "create table BAZ (" +
                "PKCOL1 integer not null, " +
                "PKCOL2 integer not null, " +
                "constraint pk_tree2 primary key (PKCOL1, PKCOL2)" +
                ");\n"
                );
        boolean success = builder.compile(pathToCatalog, 2, 1, 0);
        assertTrue("Schema compilation failed", success);
        MiscUtils.copyFile(builder.getPathToDeployment(), pathToDeployment);

        VoltDB.Configuration config = new VoltDB.Configuration();
        config.m_pathToCatalog = pathToCatalog;
        config.m_pathToDeployment = pathToDeployment;

        try {
            startSystem(config);

            // Basic alter drop, should work
            assertTrue(doesColumnExist("FOO", "DROPME"));
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column DROPME;");
            }
            catch (ProcCallException pce) {
                fail("Should be able to drop a bare column.");
            }
            assertFalse(doesColumnExist("FOO", "DROPME"));

            // but not twice
            boolean threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column DROPME;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            assertTrue("Shouldn't be able to drop a column that doesn't exist", threw);
            assertFalse(doesColumnExist("FOO", "DROPME"));

            // Can't drop column used by procedure
            assertTrue(doesColumnExist("FOO", "PROCCOL"));
            threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column PROCCOL;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            assertTrue("Shouldn't be able to drop a column used by a procedure", threw);
            assertTrue(doesColumnExist("FOO", "PROCCOL"));
            try {
                m_client.callProcedure("BAR");
            }
            catch (ProcCallException pce) {
                fail("Procedure should still exist.");
            }

            // Can't drop a column used by a view
            assertTrue(doesColumnExist("FOO", "VIEWCOL"));
            threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column VIEWCOL;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            assertTrue("Shouldn't be able to drop a column used by a view", threw);
            assertTrue(doesColumnExist("FOO", "VIEWCOL"));
            assertTrue(findTableInSystemCatalogResults("FOOVIEW"));

            // single-column indexes get cascaded automagically
            assertTrue(doesColumnExist("FOO", "INDEXCOL"));
            assertTrue(findIndexInSystemCatalogResults("FOODEX"));
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column INDEXCOL;");
            }
            catch (ProcCallException pce) {
                fail("Should be able to drop a single column backing a single column index.");
            }
            assertFalse(doesColumnExist("FOO", "INDEXCOL"));
            assertFalse(findIndexInSystemCatalogResults("FOODEX"));

            // single-column primary keys get cascaded automagically
            assertTrue(doesColumnExist("FOO", "PKCOL"));
            assertTrue(findIndexInSystemCatalogResults("VOLTDB_AUTOGEN_CONSTRAINT_IDX_PK_TREE"));
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column PKCOL;");
            }
            catch (ProcCallException pce) {
                fail("Should be able to drop a single column backing a single column primary key.");
            }
            assertFalse(doesColumnExist("FOO", "PKCOL"));
            assertFalse(findIndexInSystemCatalogResults("VOLTDB_AUTOGEN_CONSTRAINT_IDX_PK_TREE"));

            // Can't drop a column used by a multi-column index
            assertTrue(doesColumnExist("FOO", "INDEX1COL"));
            assertTrue(findIndexInSystemCatalogResults("FOO2DEX"));
            threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table FOO drop column INDEX1COL;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            System.out.println("COLUMNS: " + m_client.callProcedure("@SystemCatalog", "COLUMNS").getResults()[0]);
            System.out.println("INDEXES: " + m_client.callProcedure("@SystemCatalog", "INDEXINFO").getResults()[0]);
            //assertTrue("Shouldn't be able to drop a column used by a multi-column index", threw);
            //assertTrue(doesColumnExist("FOO", "INDEX1COL"));
            //assertTrue(findIndexInSystemCatalogResults("FOO2DEX"));

            // Can't drop a column used by a multi-column primary key
            assertTrue(doesColumnExist("BAZ", "PKCOL1"));
            assertTrue(findIndexInSystemCatalogResults("VOLTDB_AUTOGEN_CONSTRAINT_IDX_PK_TREE2"));
            threw = false;
            try {
                m_client.callProcedure("@AdHoc",
                        "alter table BAZ drop column PKCOL1;");
            }
            catch (ProcCallException pce) {
                threw = true;
            }
            System.out.println("COLUMNS: " + m_client.callProcedure("@SystemCatalog", "COLUMNS").getResults()[0]);
            System.out.println("INDEXES: " + m_client.callProcedure("@SystemCatalog", "INDEXINFO").getResults()[0]);
            assertTrue("Shouldn't be able to drop a column used by a multi-column primary key", threw);
            assertTrue(doesColumnExist("BAZ", "PKCOL1"));
            assertTrue(findIndexInSystemCatalogResults("VOLTDB_AUTOGEN_CONSTRAINT_IDX_PK_TREE2"));

        }
        finally {
            teardownSystem();
        }
    }
}
