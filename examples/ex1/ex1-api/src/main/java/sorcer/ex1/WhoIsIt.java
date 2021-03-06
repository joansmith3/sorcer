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
package sorcer.ex1;

import java.rmi.Remote;
import java.rmi.RemoteException;

import sorcer.service.Context;
import sorcer.service.ContextException;

public interface WhoIsIt extends Remote {

	Context getHostName(Context context) throws RemoteException,
			ContextException;

	Context getHostAddress(Context context) throws RemoteException,
			ContextException;

	Context getCanonicalHostName(Context context) throws RemoteException,
			ContextException;

	Context getTimestamp(Context context) throws RemoteException,
			ContextException;
}
