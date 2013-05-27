/**
 *
 * Copyright 2013 the original author or authors.
 * Copyright 2013 Sorcersoft.com S.A.
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
package sorcer.ex2.requestor;

import java.rmi.RMISecurityManager;
import java.util.logging.Logger;

import sorcer.core.SorcerEnv;
import sorcer.core.context.ServiceContext;
import sorcer.core.exertion.NetJob;
import sorcer.core.exertion.NetTask;
import sorcer.core.signature.NetSignature;
import sorcer.ex2.provider.InvalidWork;
import sorcer.ex2.provider.Work;
import sorcer.service.*;
import sorcer.util.Sorcer;
import sorcer.util.Log;

public class WorkSeqJobApp {

	private static Logger logger = Log.getTestLog();

	public static void main(String[] args) throws Exception {
		System.setSecurityManager(new RMISecurityManager());
		// initialize system properties
		Sorcer.getEnvProperties();
		
		// get the queried provider name from the command line
		String pn1 = Sorcer.getSuffixedName(args[0]);
		String pn2 = Sorcer.getSuffixedName(args[1]);
		String pn3 = Sorcer.getSuffixedName(args[2]);
		
		logger.info("Provider name1: " + pn1);
		logger.info("Provider name2: " + pn2);
		logger.info("Provider name3: " + pn3);

		Exertion result = new WorkSeqJobApp()
			.getExertion(pn1, pn2, pn3).exert();
		// get contexts of component exertions - in this case tasks
		logger.info("Output context1: \n" + result.getContext("work1"));
		logger.info("Output context2: \n" + result.getContext("work2"));
		logger.info("Output context3: \n" + result.getContext("work3"));
	}

	private Exertion getExertion(String pn1, String pn2, String pn3) throws Exception {
		String hostname = SorcerEnv.getLocalHost().getHostName();

        Work work1 = new Work() {
            public Context exec(Context cxt) throws InvalidWork, ContextException {
                int arg1 = (Integer)cxt.getValue("requestor/operand/1");
                int arg2 = (Integer)cxt.getValue("requestor/operand/2");
                cxt.putOutValue("provider/result", arg1 + arg2);
                return cxt;
            }
        };

        Work work2 = new Work() {
            public Context exec(Context cxt) throws InvalidWork, ContextException {
                int arg1 = (Integer)cxt.getValue("requestor/operand/1");
                int arg2 = (Integer)cxt.getValue("requestor/operand/2");
                cxt.putOutValue("provider/result", arg1 * arg2);
                return cxt;
            }
        };

        Work work3 = new Work() {
            public Context exec(Context cxt) throws InvalidWork, ContextException {
                int arg1 = (Integer)cxt.getValue("requestor/operand/1");
                int arg2 = (Integer)cxt.getValue("requestor/operand/2");
                cxt.putOutValue("provider/result", arg1 - arg2);
                return cxt;
            }
        };

        Context context1 = new ServiceContext("work1");
		context1.putValue("requstor/name", hostname);
		context1.putValue("requestor/operand/1", 1);
		context1.putValue("requestor/operand/2", 1);
		context1.putValue("to/provider/name", pn1);
        context1.putValue("requestor/work", work1);

        Context context2 = new ServiceContext("work2");
		context2.putValue("requstor/name", hostname);
		context2.putValue("requestor/operand/1", 2);
		context2.putValue("requestor/operand/2", 2);
		context2.putValue("to/provider/name", pn2);
        context2.putValue("requestor/work", work2);

        Context context3 = new ServiceContext("work3");
		context3.putValue("requstor/name", hostname);
		context3.putValue("requestor/operand/1", 3);
		context3.putValue("requestor/operand/2", 3);
		context3.putValue("to/provider/name", pn3);
        context3.putValue("requestor/work", work3);

        NetSignature signature1 = new NetSignature("doWork",
				sorcer.ex2.provider.Worker.class, pn1);
		NetSignature signature2 = new NetSignature("doWork",
				sorcer.ex2.provider.Worker.class, pn2);
		NetSignature signature3 = new NetSignature("doWork",
				sorcer.ex2.provider.Worker.class, pn3);
		
		Task task1 = new NetTask("work1", signature1, context1);
		Task task2 = new NetTask("work2", signature2, context2);
		Task task3 = new NetTask("work3", signature3, context3);
		Job job = new NetJob();
		job.addExertion(task1);
		job.addExertion(task2);
		job.addExertion(task3);
		return job;
	}
}