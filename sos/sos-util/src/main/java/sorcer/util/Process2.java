/*
 * Copyright 2013, 2014 Sorcersoft.com S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sorcer.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;

/**
 * @author Rafał Krupiński
 */
public class Process2 extends Process{
	final private Process process;
    private ThreadGroup helperThreads;

    public Process2(Process process) {
        this.process = process;
    }

    public Process2(Process process, ThreadGroup helperThreads) {
        this(process);
        this.helperThreads = helperThreads;
	}

	public boolean running() {
		try {
			process.exitValue();
            killThreads();
			return false;
		} catch (IllegalThreadStateException ignore) {
			return true;
		}
	}

    public int waitFor() throws InterruptedException {
        try {
		    return process.waitFor();
        } finally {
            killThreads();
        }
	}

    /**
	 * {@link Process#waitFor()} with timeout
	 *
	 * @param timeout timeout in milliseconds
	 * @return process's exit value, or null if process was destroyed after timeout
	 * @throws InterruptedException if the waiting thread was interrupted
	 */
	public Integer waitFor(long timeout) throws InterruptedException {
		/*
		* Normal waitFor() calls wait() in loop until its internal thread detects process is destroyed, in which case it calls
		* notifyAll().
		* We're calling wait() and wait for someone to call notify(). It might be either because process has ended or because our
		* NotifyTask has triggered. In the latter case timeout has passed, process hasn't finished and must be destroyed.
		*/
		if (timeout > 0) {
			new Timer().schedule(new NotifyTask(process), timeout);
			synchronized (process) {
				//in case vary small value of timeout and notify is called before wait
				process.wait(timeout);
			}
		}
		return exitValueOrDestroy();
	}

	private Integer exitValueOrDestroy() {
		try {
			return process.exitValue();
		} catch (IllegalThreadStateException ignore) {
			process.destroy();
			return process.exitValue();
        } finally {
            killThreads();
		}
	}

	public Integer exitValueOrNull() {
		try {
            int exitValue = process.exitValue();
            killThreads();
            return exitValue;
		} catch (IllegalThreadStateException ignore) {
			return null;
		}
	}

	public int destroyAndExitCode() {
		process.destroy();
        try {
            return process.waitFor();
        } catch (InterruptedException ignore) {
            return -1;
        }
    }

    private void killThreads() {
        if (helperThreads != null && helperThreads.activeCount() > 0) {
            try {
                helperThreads.destroy();
            } catch (IllegalThreadStateException ignore) {
            }
        }
	}

    @Override
    public OutputStream getOutputStream() {
        return process.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return process.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return process.getErrorStream();
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }

    @Override
    public void destroy() {
        process.destroy();
    }
}
