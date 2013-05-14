/*
 * Copyright 2010 the original author or authors.
 * Copyright 2010 SorcerSoft.org.
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

package sorcer.core.exertion;

import net.jini.core.entry.Entry;
import net.jini.id.Uuid;
import net.jini.lookup.entry.Name;
import sorcer.core.signature.NetSignature;
import sorcer.service.ExecState;
import sorcer.service.Exertion;
import sorcer.service.ServiceExertion;

import javax.security.auth.Subject;

public class ExertionEnvelop implements Entry {

	private static final long serialVersionUID = -7997472556200082660L;

	public Class serviceType;

	public String providerName;

	public Uuid exertionID;

	public Uuid parentID;

	public Boolean isEncrypted;

	public Boolean isJob;

	public Integer state;

	public Exertion exertion;

    public Entry entry;

    public byte[] encryptedExertion;

	public Subject providerSubject;

	public static ExertionEnvelop getTemplate() {
		ExertionEnvelop ee = new ExertionEnvelop();
		ee.state = new Integer(ExecState.INITIAL);

		return ee;
	}
	
	public static ExertionEnvelop getTemplate(Class serviceType,
			String providerName) {
		ExertionEnvelop ee = getTemplate();
		ee.serviceType = serviceType;
		ee.providerName = providerName;
		return ee;
	}
	
	/**
	 * Create a template with an exertionID as the exertions's parentID.
	 * 
	 * @param exertionID
	 * @param providerName
	 * @return
	 */
	public static ExertionEnvelop getParentTemplate(Uuid exertionID,
			String providerName) {
		ExertionEnvelop ee = getTemplate();
		ee.parentID = exertionID;
		ee.providerName = providerName;
		return ee;
	}
	
	/**
	 * Create a template for exertions.
	 * 
	 * @param exertionID
	 * @param providerName
	 * @return
	 */
	public static ExertionEnvelop getTemplate(Uuid exertionID,
			String providerName) {
		ExertionEnvelop ee = getTemplate();
		ee.exertionID = exertionID;
		ee.providerName = providerName;
		return ee;
	}

	public static ExertionEnvelop getTemplate(Exertion ex) {
		if (ex == null || ex.getProcessSignature() == null)
			return null;

		ExertionEnvelop ee = getTemplate();
		NetSignature ss = (NetSignature) ex.getProcessSignature();
		
		ee.exertion = ex;
		ee.serviceType = ss.getServiceType();
		ee.providerName = ss.getProviderName();
		ee.exertionID = ex.getId();
		ee.parentID = ((ServiceExertion) ex).getParentId();
		ee.isJob = new Boolean(ex.isJob());

		return ee;
	}

	/**
	 * Simple method that generates the basic template to retrieve a specific
	 * completed ExertionEnvelop from the space
	 * 
	 * @param parentID
	 *            This ID  will be the basis for creating the
	 *            template for picking up task/job from the space
	 * @return ExertionEnvelop Returns the template for picking up task/job from
	 *         the space, state is set to DONE
	 */
	public static ExertionEnvelop getTakeTemplate(Uuid parentID, Uuid childID) {
		ExertionEnvelop ee = ExertionEnvelop.getTemplate();
		ee.parentID = parentID;
		ee.exertionID = childID;
		ee.state = new Integer(ExecState.DONE); // must be set to DONE (completed)

		return ee;
	}

	public Entry[] getAttributes() {
		Entry[] attrs = { new Name(serviceType.getName()), new Name(providerName) };
		return attrs;
	}

	public long resultLeaseTime() {
		return Long.MAX_VALUE;
	}
	
	public String describe() {
		StringBuffer sb = new StringBuffer("\nExertionEnvelop: ");
		sb.append("exertionID=").append(exertionID)
		.append(", isJob=").append(isJob)
		.append(", state=").append(state)
		.append(", providerName=").append(providerName)
		.append(", parentID=").append(parentID)
		.append(", serviceType=").append(serviceType)
		.append(", isEncrypted=").append(isEncrypted)
		.append(", encryptedExertion=").append(encryptedExertion)
		.append(", providerSubject=").append(providerSubject)
		.append(", exertion=").append(exertion);
		return sb.toString();
	}
}