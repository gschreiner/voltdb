/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
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
package org.voltdb.regressionsuites;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.voltdb.BackendTarget;
import org.voltdb.VoltDB;

public class EEProcess {
    private Process m_eeProcess;
    private String m_eePID = null;
    private Thread m_stderrParser = null;
    private Thread m_stdoutParser = null;
    // Has a received String from Valgrind saying that all heap blocks were
    // freed
    // ignored when running directly againsts the IPC client
    private boolean m_allHeapBlocksFreed = false;
    private int m_port;

    private final boolean verbose = true;

    public int port() {
        return m_port;
    }

    /**
     * Exposes error generated by the Valgrind client to
     * org.voltdb.regressionsuites.LocalSingeProcessServer which will fail a
     * test if this list is not empty.
     */
    public static final List<String> m_valgrindErrors = Collections
            .synchronizedList(new ArrayList<String>());

    EEProcess(final BackendTarget target, int siteCount, String logfile) {
        if (target != BackendTarget.NATIVE_EE_VALGRIND_IPC) {
            return;
        }

        if (verbose) {
            System.out.println("Running " + target);
        }
        final ArrayList<String> args = new ArrayList<String>();
        final String voltdbIPCPath = //System.getenv("VOLTDBIPC_PATH");
                "/Users/jhugg/Documents/workspace/voltdb2/obj/debug/prod/voltdbipc";

        /*args.add("valgrind");
        args.add("--leak-check=full");
        args.add("--show-reachable=yes");
        args.add("--num-callers=32");
        args.add("--error-exitcode=-1");*/
        /*
         * VOLTDBIPC_PATH is set as part of the regression suites and ant
         * check In that scenario junit will handle logging of Valgrind
         * output. stdout and stderr is forwarded from the backend.
         */
        if (voltdbIPCPath == null) {
            args.add("--quiet");
            args.add("--log-file=" + logfile);
        }
        args.add(voltdbIPCPath == null ? "./voltdbipc" : voltdbIPCPath);
        args.add(String.valueOf(siteCount));

        final ProcessBuilder pb = new ProcessBuilder(args);
        //pb.redirectErrorStream(true);

        try {
            m_eeProcess = pb.start();
            final Process p = m_eeProcess;
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    p.destroy();
                }
            });
        } catch (final IOException e) {
            VoltDB.crashLocalVoltDB(e.getMessage(), true, e);
        }

        final BufferedReader stderr = new BufferedReader(new InputStreamReader(
                                                                               m_eeProcess.getErrorStream()));

        /*
         * This block attempts to read the PID and then waits for the
         * listening message indicating that the IPC EE is ready to accept a
         * connection on a socket
         */
        final BufferedReader stdout = new BufferedReader(new InputStreamReader(
                                                                               m_eeProcess.getInputStream()));
        try {
            boolean failure = false;
            String pidString = stdout.readLine();
            if (pidString == null) {
                failure = true;
            } else {
                if (verbose) {
                    System.out.println("PID string \"" + pidString + "\"");
                }
                pidString = pidString.substring(2);
                pidString = pidString.substring(0, pidString.indexOf("="));
                m_eePID = pidString;
            }

            String siteCountString = stdout.readLine();
            if (siteCountString == null) {
                failure = true;
            } else {
                if (verbose) {
                    System.out.println("Site count string \"" + siteCountString + "\"");
                }
                siteCountString = siteCountString.substring(2);
                siteCountString = siteCountString.substring(0, siteCountString.indexOf("="));
                int siteCount2 = Integer.valueOf(siteCountString);
                assert(siteCount2 == siteCount);
            }

            String portString = stdout.readLine();
            if (portString == null) {
                failure = true;
            } else {
                if (verbose) {
                    System.out.println("Port string \"" + portString + "\"");
                }
                portString = portString.substring(2);
                portString = portString.substring(0,
                                                  portString.indexOf("="));
                m_port = Integer.valueOf(portString);
            }

            while (true) {
                String line = null;
                if (!failure) {
                    line = stdout.readLine();
                }
                if (line != null && !failure) {
                    if (verbose) {
                        System.out.println("[ipc=" + m_eePID + "]:::" + line);
                    }
                    if (line.contains("listening")) {
                        break;
                    } else {
                        continue;
                    }
                } else {
                    while ((line = stderr.readLine()) != null) {
                        if (verbose) {
                            System.err.println(line);
                        }
                    }
                    try {
                        m_eeProcess.waitFor();
                    } catch (final InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                    VoltDB.crashLocalVoltDB("[ipc=" + m_eePID
                            + "] Returned end of stream and exit value "
                            + m_eeProcess.exitValue(), false, null);
                }
            }
        } catch (final IOException e) {
            e.printStackTrace();
            return;
        }

        /*
         * Create a thread to parse Valgrind's output and populdate
         * m_valgrindErrors with errors.
         */
        final Process p = m_eeProcess;
        m_stdoutParser = new Thread() {
            @Override
            public void run() {
                while (true) {
                    try {
                        final String line = stdout.readLine();
                        if (line != null) {
                            if (verbose) {
                                System.out.println("[ipc=" + p.hashCode()
                                                   + "]:::" + line);
                            }
                        } else {
                            try {
                                p.waitFor();
                            } catch (final InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (verbose) {
                                System.out
                                .println("[ipc="
                                         + m_eePID
                                         + "] Returned end of stream and exit value "
                                         + p.exitValue());
                            }
                            return;
                        }
                    } catch (final IOException e) {
                        e.printStackTrace();
                        return;
                    }
                }
            }
        };

        m_stderrParser = new Thread() {
            @Override
            public void run() {
                while (true) {
                    try {
                        final String line = stderr.readLine();
                        if (line != null) {
                            if (verbose) {
                                System.err.println("[ipc=" + p.hashCode()
                                                   + "]:::" + line);
                            }
                            if (line.startsWith("==" + m_eePID + "==")) {
                                processValgrindOutput(line);
                            }
                        } else {
                            try {
                                p.waitFor();
                            } catch (final InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (verbose) {
                                System.out
                                .println("[ipc="
                                         + m_eePID
                                         + "] Returned end of stream and exit value "
                                         + p.exitValue());
                            }
                            if (!m_allHeapBlocksFreed) {
                                m_valgrindErrors
                                .add("Not all heap blocks were freed");
                            }
                            return;
                        }
                    } catch (final IOException e) {
                        e.printStackTrace();
                        return;
                    }
                }
            }

            private void processValgrindOutput(final String line) {
                final String errorLineString = "ERROR SUMMARY: ";
                final String heapBlocksFreedString = "All heap blocks were freed";
                /*
                 * An indirect way of making sure Valgrind reports no error
                 * memory accesses
                 */
                if (line.contains(errorLineString)) {
                    final int index = line.indexOf(errorLineString)
                    + errorLineString.length();
                    final char errorNumChar = line.charAt(index);
                    if (!(errorNumChar == '0')) {
                        m_valgrindErrors.add(line);
                    }
                } else if (line.contains(heapBlocksFreedString)) {
                    m_allHeapBlocksFreed = true;
                }
            }
        };

        m_stdoutParser.setDaemon(false);
        m_stdoutParser.start();
        m_stderrParser.setDaemon(false);
        m_stderrParser.start();
    }

    public void destroy() {
        if (m_eeProcess != null) {
            m_eeProcess.destroy();
        }
    }

    public void waitForShutdown() throws InterruptedException {
        if (m_eeProcess != null) {
            boolean done = false;
            while (!done) {
                try {
                    m_eeProcess.waitFor();
                    done = true;
                } catch (InterruptedException e) {
                    System.out
                            .println("Interrupted waiting for EE IPC process to die. Wait again.");
                }
            }
        }
        if (m_stdoutParser != null) {
            m_stdoutParser.join();
        }
        if (m_stderrParser != null) {
            m_stderrParser.join();
        }
    }
}
