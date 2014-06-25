 /*
 * Copyright 2013 the original author or authors.
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
package sorcer.core;

import net.jini.core.lease.UnknownLeaseException;
import net.jini.id.Uuid;
import net.jini.lease.LeaseRenewalManager;
import sorcer.core.dispatch.ExertionListener;

public interface Dispatcher {
    void exec();
    DispatchResult getResult();

    ///void addExertionListener(Uuid exertionId, ExertionListener listener);
    //void removeExertionListener(Uuid exertionId);
    LeaseRenewalManager getLrm();
    void setLrm(LeaseRenewalManager lrm);
}
