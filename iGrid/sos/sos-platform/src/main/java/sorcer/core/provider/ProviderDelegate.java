/*
 * Copyright 2009 the original author or authors.
 * Copyright 2009 SorcerSoft.org.
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
package sorcer.core.provider;

import com.sun.jini.config.Config;
import groovy.lang.GroovyShell;
import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.core.entry.Entry;
import net.jini.core.event.RemoteEvent;
import net.jini.core.lease.Lease;
import net.jini.core.lookup.ServiceID;
import net.jini.core.transaction.Transaction;
import net.jini.core.transaction.TransactionException;
import net.jini.core.transaction.server.TransactionManager;
import net.jini.export.Exporter;
import net.jini.id.Uuid;
import net.jini.id.UuidFactory;
import net.jini.jeri.BasicILFactory;
import net.jini.jeri.BasicJeriExporter;
import net.jini.jeri.tcp.TcpServerEndpoint;
import net.jini.lease.LeaseRenewalManager;
import net.jini.lookup.entry.Location;
import net.jini.lookup.entry.Name;
import net.jini.security.AccessPermission;
import net.jini.security.TrustVerifier;
import net.jini.space.JavaSpace05;
import sorcer.core.*;
import sorcer.core.Provider;
import sorcer.core.context.ContextManagement;
import sorcer.core.context.Contexts;
import sorcer.core.context.ControlContext;
import sorcer.core.context.ServiceContext;
import sorcer.core.dispatch.JobThread;
import sorcer.core.exertion.ExertionEnvelop;
import sorcer.core.exertion.Jobs;
import sorcer.core.exertion.NetTask;
import sorcer.core.exertion.ObjectJob;
import sorcer.core.loki.member.LokiMemberUtil;
import sorcer.core.misc.MsgRef;
import sorcer.core.provider.ServiceProvider.ProxyVerifier;
import sorcer.core.provider.jobber.ServiceJobber;
import sorcer.core.provider.logger.RemoteHandler;
import sorcer.core.provider.proxy.Partnership;
import sorcer.core.provider.proxy.ProviderProxy;
import sorcer.core.signature.NetSignature;
import sorcer.core.signature.ServiceSignature;
import sorcer.jini.jeri.SorcerILFactory;
import sorcer.jini.lookup.entry.SorcerServiceInfo;
import sorcer.security.sign.SignedServiceTask;
import sorcer.security.sign.SignedTaskInterface;
import sorcer.security.sign.TaskAuditor;
import sorcer.security.util.SorcerPrincipal;
import sorcer.service.*;
import sorcer.service.Signature;
import sorcer.service.SignatureException;
import sorcer.util.*;
import sorcer.util.dbac.ProxyProtocol;
import sorcer.util.dbac.ServletProtocol;
import sorcer.util.dbac.SocketProtocol;

import javax.security.auth.Subject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.ExportException;
import java.security.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

import static sorcer.eo.operator.sig;

/**
 * There are two types of SORCER service servers: generic service servers -
 * subclasses of {@link ServiceProvider} - and service beans. This class does the
 * actual work for both generic SORCER providers and SORCER service beans. Also it
 * provides the basic functionality for {@link Provider}s. Multiple SORCER
 * exerters can be deployed within a single (@link ServiceProvider}.
 *
 * @see sorcer.core.provider.ServiceProvider
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class ProviderDelegate implements SorcerConstants {

	private static ThreadGroup threadGroup = new ThreadGroup(PROVIDER_THREAD_GROUP);

	static final int TRY_NUMBER = 5;
	
	// service class loader
	private ClassLoader implClassLoader;

	// visited exertion for forwardExertion to check for potential looping
	private static Set visited;

	private static final Logger logger = Log.getProviderLog();

	private volatile boolean running = true;

	private Logger remoteLogger;

	/** Provider logger used in custom provider methods */
	private Logger providerLogger;

	/** Context logger used in custom provider methods */
	private Logger contextLogger;

	/** Provider deployment configuration. */
	protected DeploymentConfiguration config = new DeploymentConfiguration();

	/** The unique ID for this server proxy verification. */
	private Uuid serverUuid;

	protected String[] groupsToDiscover;

	protected JavaSpace05 space;

	protected TransactionManager tManager;

	protected boolean workerTransactional = false;

	protected String spaceGroup;

	protected String spaceName;

	protected Class[] publishedServiceTypes;

	/** provider service type entry used to be included in the provider's proxy. */
	protected SorcerServiceInfo serviceType;

	protected boolean idPersistent = false;

	/** if true then we match all entries with interface names only. */
	protected boolean matchInterfaceOnly = false;

	/** if true then its provider can be monitored for its exerting behavior. */
	protected boolean monitorable = false;

	/** if true then its provider can produce notification. */
	protected boolean notifying = false;

	/* use Spacer workers, when false no space computing support. */
	protected boolean spaceEnabled = false;

	protected boolean spaceReadiness = false;

	protected boolean spaceSecurityEnabled = false;

	private ThreadGroup namedGroup, interfaceGroup;

	private int workerCount = 10;

	private int[] workerPerInterfaceCount = new int[0];

	private int queueSize = 0;

	private int maximumPoolSize = 20;

	private List<ExecutorService> spaceHandlingPools;

	/** HTTP connectivity to the portal */
	private ProxyProtocol protocol;

	/** The SORCER persistence server. */
	public static Mandator persister;

	/** directory for provider temp files. */
	private static final String SORCER_TMP_DIR = "sorcertmp";

	/** lease manager also used by provider workers. */
	protected static LeaseRenewalManager leaseManager = new LeaseRenewalManager();

	protected sorcer.core.Provider provider;

	protected boolean mutualExclusion = true;

	// all exported services with corresponding exporter
	// <Remote, Exporter> or <service bean, service provider>
	private static Map exports = new HashMap();

	protected Remote providerProxy;

	private long eventID = 0, seqNum = 0;

	private List<Entry> extraLookupAttributes = new Vector<Entry>();

	/** Map of exertion ID's and state of execution */
	private static final Map exertionStateTable = Collections
			.synchronizedMap(new HashMap(11));
	/**
	 * A smart proxy instance
	 */
	private Object smartProxy = null;

	/**
	 * A {@link Remote} partner object expending functionality of this provider.
	 * The provider's inner proxy can be used by the outer proxy of this
	 * provider to make remote redirectional calls on this partner.
	 */
	private Remote partner = null;

	/**
	 * A remote inner proxy implements Remote interface. Usually outer proxy
	 * complements its functionality by invoking remote calls on the inner proxy
	 * server. Thus, inner proxy can make remote calls on another service
	 * provider, for example {@link Servicer#service), while the
	 * outer proxy still can call directly on the originating service provider.
	 */
	private Remote innerProxy = null;

	/**
	 * An outer service proxy, by default the proxy of this provider, is used
	 * from by service requestors if provider's smart proxy is absent. At least
	 * two generic Remote interface: {@link Servicer} and {@link Provider} are
	 * implemented by outer proxies of all SORCER service providers. Each SORCER
	 * provider uses outer proxy to actually call directly its provider and make
	 * redirected calls using its inner proxy (redirected remote invocations).
	 * Any method of not Remote interface implemented by a SORCER service
	 * provider can be invoked via the Servicer remote interface,
	 * {@link Servicer#service} - recommended approach. That
	 * provider's direct invocation method is embedded into a service method of
	 * the provided exertion.
	 */
	private Remote outerProxy = null;

	/** The exporter for exporting and unexporting outer proxy */
	private Exporter outerExporter;

	private SorcerILFactory ilFactory;

	/** The exporter for exporting and unexporting inner proxy */
	private Exporter partnerExporter;

	/**
	 * The admin proxy handles the standard Jini Admin interface.
	 */
	private Remote adminProxy;

	/**
	 * SORCER service beans instantiated by this delegate
	 */
	private Object[] serviceBeans;

	/**
	 * Exposed service type components. A key is an interface and a value its
	 * implementing service-object.
	 */
	private Map serviceComponents;

	/**
	 * List of Exertions for which SLA Offer was given
	 */
	private List exertionsGivenSlaList = Collections
			.synchronizedList(new ArrayList());

	/**
	 * Indicates a single threaded execution for service beans or providers
	 * implementing the SingleThreadModel interface.
	 */
	private boolean singleThreadModel = false;

	private String hostName, hostAddress;

	private ContextManagement contextManager;

	/*
	 * A nested class to hold the state information of the executing thread for
	 * a served exertion.
	 */
	public static class ExertionSessionInfo {

		static LeaseRenewalManager lrm = new LeaseRenewalManager();

		private static class ExertionSessionBundle {
			public Uuid exertionID;
			public MonitoringSession session;
		}

		private static final ThreadLocal<ExertionSessionBundle> tl = new ThreadLocal<ExertionSessionBundle>() {
			@Override
			protected ExertionSessionBundle initialValue() {
				return new ExertionSessionBundle();
			}
		};

		public static void add(ServiceExertion ex) {
			ExertionSessionBundle esb = (ExertionSessionBundle) tl.get();
			esb.exertionID = ex.getId();
			esb.session = (MonitoringSession) ex.getMonitorSession();
			if (ex.getMonitorSession() != null)
				lrm.renewUntil(
						((MonitoringSession) ex.getMonitorSession()).getLease(),
						Lease.ANY, null);
		}

		public static MonitoringSession getSession() {
			ExertionSessionBundle esb = (ExertionSessionBundle) tl.get();
			return (esb != null) ? esb.session : null;
		}

		public static Uuid getID() {
			ExertionSessionBundle esb = (ExertionSessionBundle) tl.get();
			return (esb != null) ? esb.exertionID : null;
		}

		public static void removeLease() {
			ExertionSessionBundle esb = (ExertionSessionBundle) tl.get();
			try {
				lrm.remove(((MonitoringSession) esb.session).getLease());
			} catch (Exception e) {
			}
		}
	}

	public ProviderDelegate() {
	}

	public boolean init(sorcer.core.Provider provider) throws RemoteException,
			ConfigurationException {
		return init(provider, null);
	}

	public boolean init(sorcer.core.Provider provider, String configFilename)
			throws RemoteException, ConfigurationException {
		this.provider = provider;
		String providerProperties = configFilename;
		// This allows us to specify different properties for different hosts
		// using a shared mounted filesystem
		if (providerProperties != null
				&& providerProperties.contains("HOSTNAME")) {
			try {
				providerProperties = providerProperties.replace("HOSTNAME",
						InetAddress.getLocalHost().getHostName());
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}

		restore();

		String str;
		// set provider's ID persistance flag if defined in provider's
		// properties
		idPersistent = Sorcer.getProperty(P_SERVICE_ID_PERSISTENT, "false")
				.equals("true");
		// set provider join groups if defined in provider's properties
		groupsToDiscover = Sorcer.getLookupGroups();
		logger.info("ServiceProvider:groups to discover="
				+ SorcerUtil.arrayToString(groupsToDiscover));
		// set provider space group if defined in provider's properties
		spaceGroup = config.getProperty(J_SPACE_GROUP, Sorcer.getSpaceGroup());
		// set provider space name if defined in provider's properties
		spaceName = config.getProperty(J_SPACE_NAME,
				Sorcer.getActualSpaceName());

		Class[] serviceTypes = new Class[0];
		try {
			serviceTypes = (Class[]) config.jiniConfig.getEntry(
					ServiceProvider.PROVIDER, J_INTERFACES, Class[].class);
		} catch (ConfigurationException e) {
			// do nothing, used the default value
			// e.printStackTrace();
		}
		if ((serviceTypes != null) && (serviceTypes.length > 0)) {
			publishedServiceTypes = serviceTypes;
			logger.info("*** published services: "
					+ Arrays.toString(publishedServiceTypes));
		}

		try {
			singleThreadModel = (Boolean) config.jiniConfig.getEntry(
					ServiceProvider.PROVIDER, J_SINGLE_TRHREADED_MODEL,
					boolean.class, false);
		} catch (ConfigurationException e) {
			// do nothing, used the default value
		}

		initDynamicServiceAccessor();
		return initSpaceSupport();
	}

	private void initDynamicServiceAccessor() {
		try {
			String val = Sorcer.getProperty(S_SERVICE_ACCESSOR_PROVIDER_NAME);
			if (val != null && val.equals(ProviderLookup.class.getName())) {
				ProviderLookup.init();
			} else if (val != null
					&& val.equals(ProviderLocator.class.getName())) {
				ProviderLocator.init();
			} else if (val != null
					&& val.equals(ProviderAccessor.class.getName())) {
				ProviderAccessor.init();
			} else if (val != null
					&& val.equals(ProviderAccessor.class.getName())) {
				ProviderAccessor.init();
			}
		} catch (AccessorException e) {
			e.printStackTrace();
		}
	}

	private boolean initSpaceSupport() throws ConfigurationException, RemoteException {
		try {
			hostName = InetAddress.getLocalHost().getHostName();
			hostAddress = InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			// ignore it
		}
		if (spaceEnabled)
			space = ProviderAccessor.getSpace(spaceName, spaceGroup);
		if (space == null) {
			int ctr = 0;
			while (space == null && ctr++ < TRY_NUMBER) {
				logger.warning("could not get space, trying again... try number = "
						+ ctr);
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				space = ProviderAccessor.getSpace(spaceName, spaceGroup);
			}
			if (space != null) {
				logger.info("got space = " + space);
			} else {
				logger.warning("***warn: could not get space...moving on.");
			}
		}
		if (workerTransactional)
			tManager = ProviderAccessor.getTransactionManager();

		if (spaceEnabled) {
			try {
				return startSpaceTakers();
			} catch (Exception e) {
				e.printStackTrace();
				logger.severe("Provider HALTED: Couldn't start Workers");
				provider.destroy();
			}
		}
		return true;
	}

	protected void configure(Configuration jconfig) throws ExportException {
		final Thread currentThread = Thread.currentThread();
		implClassLoader = currentThread.getContextClassLoader();
		Class partnerType = null;
		String partnerName = null;
		boolean remoteContextLogging = false;

		try {
			remoteContextLogging = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, REMOTE_CONTEXT_LOGGING,
					boolean.class, false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}
		if (remoteContextLogging) {
			initContextLogger();
		}

		boolean remoteProviderLogging = false;
		try {
			remoteProviderLogging = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, REMOTE_PROVIDER_LOGGING,
					boolean.class, false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}
		if (remoteProviderLogging) {
			initProviderLogger();
		}

		boolean remoteLogging = false;
		try {
			remoteLogging = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, REMOTE_LOGGING, boolean.class,
					false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}
		if (remoteLogging) {
			String managerName, loggerName;
			try {
				managerName = (String) jconfig.getEntry(
						ServiceProvider.COMPONENT, REMOTE_LOGGER_MANAGER_NAME,
						String.class, "*");
				Level level = (Level) jconfig.getEntry(
						ServiceProvider.COMPONENT, REMOTE_LOGGER_LEVEL,
						java.util.logging.Level.class, Level.ALL);
				loggerName = (String) jconfig.getEntry(
						ServiceProvider.COMPONENT, REMOTE_LOGGER_NAME,
						String.class,
						"remote.sorcer.provider-" + provider.getProviderName());

				initRemoteLogger(level, managerName, loggerName);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		try {
			monitorable = (Boolean) jconfig.getEntry(ServiceProvider.COMPONENT,
					PROVIDER_MONITORING, boolean.class, false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			notifying = (Boolean) jconfig.getEntry(ServiceProvider.COMPONENT,
					PROVIDER_NOTIFYING, boolean.class, false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			mutualExclusion = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, MUTUAL_EXCLUSION, boolean.class,
					false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			matchInterfaceOnly = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, INTERFACE_ONLY, boolean.class,
					false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			spaceEnabled = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, SPACE_ENABLED, boolean.class,
					false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}
		try {
			workerCount = (Integer) jconfig.getEntry(ServiceProvider.PROVIDER,
					WORKER_COUNT, int.class, 10);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			workerPerInterfaceCount = (int[]) jconfig.getEntry(
					ServiceProvider.PROVIDER, WORKER_PER_INTERFACE_COUNT,
					int[].class, new int[0]);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			queueSize = (Integer) jconfig.getEntry(ServiceProvider.PROVIDER,
					SPACE_WORKER_QUEUE_SIZE, int.class, 0);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			maximumPoolSize = (Integer) jconfig.getEntry(
					ServiceProvider.PROVIDER, MAX_WORKER_POOL_SIZE, int.class,
					20);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			spaceReadiness = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, SPACE_READINESS, boolean.class,
					false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			workerTransactional = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, WORKER_TRANSACTIONAL,
					boolean.class, false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			spaceSecurityEnabled = (Boolean) jconfig.getEntry(
					ServiceProvider.COMPONENT, SPACE_SECURITY_ENABLED,
					boolean.class, false);
		} catch (Exception e) {
			// do nothing, default value is used
			e.printStackTrace();
		}

		try {
			contextManager = (ContextManagement) jconfig.getEntry(
					ServiceProvider.COMPONENT, CONTEXT_MANAGER,
					ContextManagement.class, null);
		} catch (Exception e) {
			e.printStackTrace();
		}
		logger.info("*** assigned dataContext manager: " + contextManager);

		try {
			partnerType = (Class) jconfig.getEntry(ServiceProvider.COMPONENT,
					SERVER_TYPE, Class.class, null);
		} catch (Exception e) {
			e.printStackTrace();
			partnerType = null;
		}
		try {
			partnerName = (String) jconfig.getEntry(ServiceProvider.COMPONENT,
					SERVER_NAME, String.class, null);
		} catch (Exception e) {
			e.printStackTrace();
			partnerName = null;
		}
		try {
			partner = (Remote) jconfig.getEntry(ServiceProvider.COMPONENT,
					SERVER, Remote.class, null);
			logger.info("partner=" + partner);
		} catch (Exception e) {
			e.printStackTrace();
			partnerName = null;
		}
		if (partner != null) {
			getPartner(partnerName, partnerType);
			exports.put(partner, partnerExporter);
		}
		// get exporters for outer and inner proxy
		getExporters(jconfig);
		logger.info("exporting provider: " + provider);
		logger.info("outerExporter = " + outerExporter);
		try {
		    outerProxy = (Remote) ProviderProxy.wrapServiceProxy(
		        outerExporter.export(provider), getServerUuid());
			logger.fine("outerProxy: " + outerProxy);
		} catch (ExportException ee) {
			logger.throwing(ProviderDelegate.class.getName(), "configure", ee);
		}
		providerProxy = outerProxy;
		adminProxy = outerProxy;
		exports.put(outerProxy, outerExporter);
		logger.fine(">>>>>>>>>>> exported outerProxy: \n" + outerProxy
				+ ", outerExporter: \n" + outerExporter);

		logger.info("PROXIES >>>>> provider: " + providerProxy + "\nsmart: "
				+ smartProxy + "\nouter: " + outerProxy + "\ninner: "
				+ innerProxy);
		// allow for enough time to export the provider's proxy and stay alive
		new Thread(threadGroup, new KeepAwake()).start();
	}

	public class KeepAwake implements Runnable {

		public void run() {
			try {
				while (running) {
					Thread.sleep(ProviderDelegate.KEEP_ALIVE_TIME);
				}
			} catch (Exception doNothing) {
			}
		}
	}

	private void initThreadGroups() {
		namedGroup = new ThreadGroup("Provider Group: " + getProviderName());
		namedGroup.setDaemon(true);
		namedGroup.setMaxPriority(Thread.NORM_PRIORITY - 1);
		interfaceGroup = new ThreadGroup("Interface Threads");
		interfaceGroup.setDaemon(true);
		interfaceGroup.setMaxPriority(Thread.NORM_PRIORITY - 1);
	}

	public boolean startSpaceTakers() throws ConfigurationException, RemoteException {
		ExecutorService spaceWorkerPool;
		spaceHandlingPools = new ArrayList<ExecutorService>();
		String msg;
		if (space == null) {
			msg = "ERROR: No space found, spaceName = " + spaceName
					+ ", spaceGroup = " + spaceGroup;
			logger.severe(msg);
			return false;
		}
		if (workerTransactional && tManager == null) {
			msg = "ERROR: no transactional manager found....";
			logger.severe(msg);
			return false;
		}
		if (publishedServiceTypes == null || publishedServiceTypes.length == 0) {
			msg = "ERROR: no published interfaces found....";
			logger.severe(msg);
			return false;
		}

		initThreadGroups();
		ExertionEnvelop envelop;
		LokiMemberUtil memberInfo = null;
		if (spaceSecurityEnabled) {
			memberInfo = new LokiMemberUtil(ProviderDelegate.class.getName());
		}

		logger.finer("*** provider worker count: " + workerCount
				+ ", spaceTransactional: " + workerTransactional);
		logger.info("publishedServiceTypes.length = "
				+ publishedServiceTypes.length);
		logger.info(GenericUtil.arrayToString(publishedServiceTypes));

		// create a pair of taker threads for each published interface
		SpaceTaker worker = null;

		// make sure that the number of core threads equals the maximum number
		// of threads
		if (queueSize == 0) {
			if (maximumPoolSize > workerCount)
				workerCount = maximumPoolSize;
		}
		for (int i = 0; i < publishedServiceTypes.length; i++) {
			// spaceWorkerPool = Executors.newFixedThreadPool(workerCount);
			spaceWorkerPool = new ThreadPoolExecutor(workerCount,
					maximumPoolSize > workerCount ? maximumPoolSize
							: workerCount, 0L, TimeUnit.MILLISECONDS,
					new LinkedBlockingQueue<Runnable>(
							(queueSize == 0 ? workerCount : queueSize)));
			spaceHandlingPools.add(spaceWorkerPool);
			// SORCER.ANY is required for a ProviderWorker
			// to avoid matching to any provider name
			// that is Java null matching everything
			envelop = ExertionEnvelop.getTemplate(publishedServiceTypes[i],
					SorcerConstants.ANY);
			if (spaceReadiness) {
				worker = new SpaceIsReadyTaker(new SpaceTaker.SpaceTakerData(
						envelop, memberInfo, provider, spaceName, spaceGroup,
						workerTransactional, queueSize == 0), spaceWorkerPool);
			} else {
				worker = new SpaceTaker(new SpaceTaker.SpaceTakerData(envelop,
						memberInfo, provider, spaceName, spaceGroup,
						workerTransactional, queueSize == 0), spaceWorkerPool);
			}
			Thread sith = new Thread(interfaceGroup, worker);
			sith.setDaemon(true);
			sith.start();
			logger.info("*** space worker-" + i + " started for: "
					+ publishedServiceTypes[i]);
			// System.out.println("space template: " +
			// envelop.describe());

			if (!matchInterfaceOnly) {
				// spaceWorkerPool = Executors.newFixedThreadPool(workerCount);
				spaceWorkerPool = new ThreadPoolExecutor(workerCount,
						maximumPoolSize > workerCount ? maximumPoolSize
								: workerCount, 0L, TimeUnit.MILLISECONDS,
						new LinkedBlockingQueue<Runnable>(
								(queueSize == 0 ? workerCount : queueSize)));
				spaceHandlingPools.add(spaceWorkerPool);
				envelop = ExertionEnvelop.getTemplate(publishedServiceTypes[i],
						getProviderName());
				if (spaceReadiness) {
					worker = new SpaceIsReadyTaker(
							new SpaceTaker.SpaceTakerData(envelop, memberInfo,
									provider, spaceName, spaceGroup,
									workerTransactional, queueSize == 0),
							spaceWorkerPool);
				} else {
					worker = new SpaceTaker(new SpaceTaker.SpaceTakerData(
							envelop, memberInfo, provider, spaceName,
							spaceGroup, workerTransactional, queueSize == 0),
							spaceWorkerPool);
				}
				Thread snth = new Thread(namedGroup, worker);
				snth.setDaemon(true);
				snth.start();
				logger.info("*** named space worker-" + i + " started for: "
						+ publishedServiceTypes[i] + ":" + getProviderName());
				// System.out.println("space template: " +
				// envelop.describe());
			}
		}
		// interfaceGroup.list();
		// namedGroup.list();
		return true;
	}

	public Task doTask(Task task, Transaction transaction)
			throws ExertionException, SignatureException, RemoteException,
			ContextException {
		// prepare a default net batch task with the last signature as master
		// SRV type
		List<Signature> alls = task.getSignatures();
		Signature lastSig = alls.get(alls.size() - 1);
		if (alls.size() > 1 && task.isConcatenated()
				&& (lastSig instanceof NetSignature)) {
			for (int i = 0; i < alls.size() - 1; i++) {
				alls.get(i).setType(Signature.PRE);
			}
		}
		task.getControlContext().appendTrace(
				provider.getProviderName() + " execute: "
						+ task.getProcessSignature().getSelector() + ":"
						+ task.getProcessSignature().getServiceType() + ":"
						+ getHostName());

		if (task instanceof SignedTaskInterface) {
			try {
				new TaskAuditor().audit((SignedServiceTask) task);
				task = (Task) ((SignedTaskInterface) task).getObject();
			} catch (Exception e) {
				logger.severe("Exception while retrieving SIGNED TASK" + e);
				e.printStackTrace();
			}
		}

		String providerId = task.getProcessSignature().getProviderName();
		/*
		 * String actions = task.method.action(); GuardedObject go = new
		 * GuardedObject(task.method, new ServiceMethodPermission(task.userID,
		 * actions)); try { Object o = go.getObject(); Util.debug(this, "Got
		 * access to method: " + actions); } catch (AccessControlException ace)
		 * { throw new ExertionMethodException ("Can't access method: " +
		 * actions); }
		 */
		if (isValidTask(task)) {
			try {
				task.startExecTime();
				exertionStateTable.put(task.getId(), new Integer(
						ExecState.RUNNING));
				if (((ServiceProvider) provider).isValidTask(task)) {
					// preprocessing
					if (task.getPreprocessSignatures().size() > 0) {
						Context cxt = preprocess(task);
						cxt.setExertion(task);
						task.setContext(cxt);
						task.setServicer(provider);
					}
					// service processing
					NetSignature tsig = (NetSignature) task
							.getProcessSignature();
					tsig.setServicer(provider);
					if (tsig.getReturnPath() != null)
						try {
							((ServiceContext) task.getDataContext())
									.setReturnPath(tsig.getReturnPath());
						} catch (ContextException e) {
							e.printStackTrace();
							throw new ExertionException(e);
						}
					if (isBeanable(task)) {
						task = useServiceComponents(task, transaction);
					} else {
						logger.info("going to execTask(); transaction = "
								+ transaction);
						task = execTask(task);
						logger.info("DONE going to execTask(); transaction = "
								+ transaction);
					}
					// postprocessing
					logger.info("postprocessing task...transaction = "
							+ transaction);
					if (task.getPostprocessSignatures().size() > 0) {
						Context cxt = postprocess(task);
						cxt.setExertion(task);
						task.setContext(cxt);
						task.setServicer(provider);
					}
					confirmExec(task);
					task.stopExecTime();
					logger.info("provider name = " + provider.getDescription()
							+ "\nreturing task; transaction = " + transaction);
					return task;
				} else {
					provider.fireEvent();
					task.stopExecTime();
					ExertionException ex = new ExertionException(
							"Unacceptable task received, requested provider: "
									+ providerId + " Name:" + task.getName());
					task.reportException(ex);
					task.setStatus(ExecState.FAILED);
					return (Task) forwardTask(task, provider);
				}
			} finally {
				exertionStateTable.remove(exertionStateTable.remove(task
						.getId()));
			}
		}
		return (Task) forwardTask(task, provider);
	}

	private Context preprocess(Task task) throws ExertionException,
			SignatureException {
		return processContinousely(task, task.getPreprocessSignatures());
	}

	private Context postprocess(Task task) throws ExertionException,
			SignatureException {
		return processContinousely(task, task.getPostprocessSignatures());
	}

	private Context processContinousely(Task task, List<Signature> signatures)
			throws ExertionException, SignatureException {
		Signature.Type st = signatures.get(0).getType();

		ObjectJob job = new ObjectJob(signatures.get(0).getType() + "-"
				+ task.getName(), sig("execute", ServiceJobber.class));
		Task t = null;
		Signature ss = null;
		for (int i = 0; i < signatures.size(); i++) {
			ss = signatures.get(i);
			if (ss instanceof NetSignature)
				((NetSignature) ss).setServicer(provider);
			try {
				t = Task.newTask(task.getName() + "-" + i, ss,
						task.getDataContext());
				ss.setType(Signature.SRV);
				((ServiceContext) task.getDataContext()).setCurrentSelector(ss
						.getSelector());
				((ServiceContext) task.getDataContext())
						.setCurrentPrefix(((ServiceSignature) ss).getPrefix());

				t.setContinous(true);
			} catch (Exception e) {
				e.printStackTrace();
				resetSigantures(signatures, st);
				throw new ExertionException(e);
			}
			job.addExertion(t);
		}
		Exertion result = null;
		try {
			// result = sj.exert();
			JobThread jobThread = new JobThread(job, provider);
			jobThread.start();
			jobThread.join();
			result = jobThread.getResult();
			// logger.info("<==== JobThread result: " + result);
		} catch (Exception e) {
			e.printStackTrace();
			resetSigantures(signatures, st);
			throw new ExertionException(e);
		}
		// append accumulated exceptions and trace
		task.getExceptions().addAll(result.getExceptions());
		task.getTrace().addAll(result.getTrace());
		if (((ServiceExertion) result).getStatus() <= ExecState.FAILED) {
			task.setStatus(ExecState.FAILED);
			ExertionException ne = new ExertionException(
					"Batch signatures failed: " + signatures);
			task.reportException(ne);
			resetSigantures(signatures, st);
			throw ne;
		}
		// return the service dataContext of the last exertion
		resetSigantures(signatures, st);
		Context ctx = result.getExertions().get(job.size() - 1).getDataContext();
		return ctx;
	}

	private void resetSigantures(List<Signature> signatures, Signature.Type type) {
		for (int i = 0; i < signatures.size(); i++) {
			signatures.get(i).setType(type);
		}
	}

	private void confirmExec(Task task) {
		String pn;
		try {
			pn = getProviderName();
			if (pn == null || pn.length() == 0)
				pn = getDescription();
			Contexts.putOutValue(task.getDataContext(), TASK_PROVIDER, pn + "@"
					+ hostName + ":" + hostAddress);
		} catch (ContextException ex) {
			// ignore
		} catch (RemoteException e) {
			// local call
		}
	}

	private boolean isBeanable(Task task) {
		if (serviceComponents == null || serviceComponents.size() == 0)
			return false;
		Class serviceType = task.getProcessSignature().getServiceType();
		Iterator i = serviceComponents.entrySet().iterator();
		Map.Entry next;
		Object impl = null;
		while (i.hasNext()) {
			next = (Map.Entry) i.next();
			logger.fine("mach serviceType: " + serviceType + " against: "
					+ next.getKey());
			// check declared interfaces
			if (next.getKey() == serviceType)
				return true;

			// check implemented interfaces
			Class[] supertypes = ((Class)next.getKey()).getInterfaces();			
			for (Class st : supertypes) {
				logger.fine("mach serviceType: " + serviceType 
						+ " against: " +  st);
				if (st == serviceType)
					return true;
			}
		}
		return false;
	}

	private Task useServiceComponents(Task task, Transaction transaction)
			throws RemoteException, ContextException {
		String selector = task.getProcessSignature().getSelector();
		Class serviceType = task.getProcessSignature().getServiceType();
		Iterator i = serviceComponents.entrySet().iterator();
		Map.Entry next;
		Object impl = null;
		while (i.hasNext()) {
			next = (Map.Entry) i.next();
			if (next.getKey() == serviceType) {
				impl = next.getValue();
				break;
			}
			Class[] supertypes = ((Class)next.getKey()).getInterfaces();			
			for (Class st : supertypes) {
				if (st == serviceType) {
					impl = next.getValue();
					break;
				}
			}
		}
		if (impl != null) {
			if (task.getProcessSignature().getReturnPath() != null) {
				try {
					((ServiceContext) task.getDataContext()).setReturnPath(task
							.getProcessSignature().getReturnPath());
				} catch (ContextException e) {
					task.reportException(e);
				}
			}
			// determine args and parameterTpes from the dataContext
			Class[] argTypes = new Class[] { Context.class };
			Object[] args = new Object[] { task.getDataContext() };
			ServiceContext cxt = (ServiceContext) task.getDataContext();
			boolean isContextual = true;
			if (cxt.getParameterTypes() != null & cxt.getArgs() != null) {
				argTypes = cxt.getParameterTypes();
				args = (Object[]) cxt.getArgs();
				isContextual = false;
			}
			Method m = null;
			try {
				m = impl.getClass().getMethod(selector, argTypes);
				logger.info("Executing service bean method: " + m + " by: "
						+ config.getProviderName());
				Context result;
				task.getDataContext().setExertion(task);
				((ServiceContext) task.getDataContext())
						.setCurrentSelector(selector);
				((ServiceContext) task.getDataContext())
						.setCurrentPrefix(((ServiceSignature) task
								.getProcessSignature()).getPrefix());

				if (isContextual) {
					result = (Context) m.invoke(impl, args);
				} else {
					result = task.getDataContext();
					((ServiceContext) result).setReturnValue(m.invoke(impl,
							args));
				}
				// clear task in the dataContext
				result.setExertion(null);
				task.setContext(result);
				task.setStatus(ExecState.DONE);
				return task;
			} catch (Exception e) {
				task.reportException(e);
				e.printStackTrace();
			}
		}
		task.setStatus(ExecState.FAILED);
		return task;
	}

	protected ServiceExertion forwardTask(ServiceExertion task,
			sorcer.core.Provider requestor) throws ExertionException,
			RemoteException, SignatureException, ContextException {
		// check if we do not look with the same exertion
		Servicer recipient = null;
		String prvName = task.getProcessSignature().getProviderName();
		NetSignature fm = (NetSignature) task.getProcessSignature();
		ServiceID serviceID = fm.getServiceID();
		Class prvType = fm.getServiceType();
		logger.info("ProviderDelegate#forwardTask \nprvType: " + prvType
				+ "\nprvName = " + prvName);

		if (visited == null)
			visited = new HashSet();

		if (visited.contains(serviceID)) {
			visited.remove(serviceID);
			throw new ExertionException("Not able to get relevant type: "
					+ prvType + ", name: " + prvName);
		}
		visited.add(serviceID);
		if (serviceComponents != null) {
			NetTask result = (NetTask) useServiceComponents((Task) task, null);
			logger.info("forwardTask executed by a service bean: " + result);
			if (result != null) {
				visited.remove(serviceID);
				return result;
			} else {
				task.setStatus(ExecState.ERROR);
				return task;
			}
		}
		if (serviceID != null)
			recipient = ProviderAccessor.getProvider(serviceID);
		else if (prvType != null && prvName != null) {
			recipient = ProviderAccessor.getProvider(prvName, prvType);
		} else if (prvType != null) {
			recipient = ProviderAccessor.getProvider(prvType);
		}
		if (recipient == null) {
			visited.remove(serviceID);
			ExertionException re = new ExertionException(
					"Not able to get provider type: " + prvType + ", name: "
							+ prvName);
			notifyException(task, "", re);
			throw re;
		} else if (recipient.getClass().getName()
				.startsWith(requestor.getClass().getName())) {
			visited.remove(serviceID);
			ExertionException re = new ExertionException(
					"Invalid task for provider type: " + prvType + ", name: "
							+ prvName + " " + task.toString());
			notifyException(task, "", re);
			throw re;
		} else
			try {
				Task result = (Task) recipient.service(task, null);
				if (result != null) {
					visited.remove(serviceID);
					return result;
				} else {
					visited.remove(serviceID);
					throw new ExertionException(
							"Not able to get relevant type: " + prvType
									+ ", name: " + prvName);
				}
			} catch (TransactionException te) {
				visited.remove(serviceID);
				throw new ExertionException("transaction failure", te);
			}
	}

	public ServiceExertion dropTask(Exertion entryTask)
			throws ExertionException, SignatureException, RemoteException {
		return null;
	}

	public static Job doJob(Job job) throws ExertionException, RemoteException,
			ContextException {
		String jobberName = job.getRendezvousName();
		Jobber jobber;
		try {
			if (jobberName != null)
				jobber = ProviderAccessor.getJobber(jobberName);
			else
				jobber = ProviderAccessor.getJobber();
		} catch (AccessorException ae) {
			ae.printStackTrace();
			throw new ExertionException(
					"Provider Delegate Could not find the Jobber");
		}

		Job outJob;
		try {
			outJob = (Job) jobber.service(job, null);
		} catch (TransactionException te) {
			throw new ExertionException("transaction failure", te);
		}

		if (((ControlContext) job.getDataContext()).isNodeReferencePreserved()) {
			Jobs.preserveNodeReferences(job, outJob);
			// copy DataNodes to object passed in
			// job.copyNodes(outJob);
			// job.setStatus(outJob.getStatus());

//			try {
//				// this is really to invoke notify observers on Variables which
//				// are being observed by a Variable, which is contained
//				// by a ResponseVariable. the Variable contained by the
//				// ResponseVariable needs to calculate the new response based
//				// on the current values in the observable Variables.
//				// when the DataNodes of those Variables were changed, nobody
//				// told those Variables...indeed, the Variables contained by
//				// ResponseVariables should be observing DataNodes...
//				// job.restoreDependencies();
//			} catch (Throwable ex) {
//				throw new RemoteException(
//						"restoring dependencies in job failed", ex);
//			}
			return job;
		} else
			return outJob;
	}

	public Job dropJob(Job job) throws RemoteException, ExertionException {
		return null;
	}

	public void hangup() throws RemoteException {
		String str = config.getProperty(P_DELAY_TIME);
		if (str != null) {
			try {
				// delay is in seconds
				int delay = Integer.parseInt(str);
				Thread.sleep(delay * 1000);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
		}
	}

	public boolean isValidMethod(String name) throws RemoteException {
		// modify name for SORCER providers
		Method[] methods = provider.getClass().getMethods();
		for (int i = 0; i < methods.length; i++) {
			if (methods[i].getName().equals(name))
				return true;
		}
		return false;
	}

	public Task execTask(Task task) throws ExertionException,
			SignatureException, RemoteException {
		ServiceContext cxt = (ServiceContext) task.getDataContext();
		try {
			if (cxt.isValid(task.getProcessSignature())) {
				Signature sig = task.getProcessSignature();
				if (sig.getReturnPath() != null)
					cxt.setReturnPath(sig.getReturnPath());

				cxt.setCurrentSelector(sig.getSelector());
				cxt.setCurrentPrefix(((ServiceSignature) sig).getPrefix());

				cxt.setExertion(task);
				task.setServicer(provider);

				if (sig instanceof NetSignature)
					((NetSignature) sig).setServicer(provider);
				task.setStatus(ExecState.FAILED);
				logger.info("DELEGATE EXECUTING TASK: " + task + " by sig: "
						+ task.getProcessSignature() + " for dataContext: " + cxt);
				cxt = (ServiceContext) invokeMethod(sig.getSelector(), cxt);
				logger.info("doTask: TASK DONE BY DELEGATE OF ="
						+ provider.getProviderName());
				task.setContext(cxt);
				task.setStatus(ExecState.DONE);
				// clear the exertion and the dataContext
				cxt.setExertion(null);
				task.setServicer(null);
				logger.info("CONTEXT GOING OUT: " + cxt);
			}
		} catch (ContextException e) {
			throw new ExertionException(e);
		}
		return task;
	}

	public Exertion invokeMethod(String selector, Exertion ex)
			throws ExertionException {
		Class[] argTypes = new Class[] { Exertion.class };
		try {
			Method m = provider.getClass().getMethod(selector, argTypes);
			logger.info("Executing method: " + m + " by: "
					+ config.getProviderName());

			Exertion result = (Exertion) m
					.invoke(provider, new Object[] { ex });
			return result;
		} catch (Exception e) {
			ex.getControlContext().addException(e);
			throw new ExertionException(e);
		}
	}

	public Context invokeMethod(String selector, Context sc)
			throws ExertionException {
		try {
			Class[] argTypes = new Class[] { Context.class };
			Object[] args = new Object[] { sc };
			ServiceContext cxt = (ServiceContext) sc;
			boolean isContextual = true;
			if (cxt.getParameterTypes() != null & cxt.getArgs() != null) {
				argTypes = cxt.getParameterTypes();
				args = (Object[]) cxt.getArgs();
				isContextual = false;
			}
			Method execMethod = provider.getClass().getMethod(selector,
					argTypes);
			Context result = null;
			if (isContextual) {
				result = (Context) execMethod.invoke(provider, args);
			} else {
				((ServiceContext) sc).setReturnValue(execMethod.invoke(
						provider, args));
				result = sc;
			}
			return result;
			// }
		} catch (Exception e) {
			throw new ExertionException(e);
		}
	}

	private void doMethodAs(Subject subject, final String methodName)
			throws java.security.PrivilegedActionException,
			AccessControlException {

		Subject.doAs(subject, new PrivilegedExceptionAction() {
			public Object run() throws Exception {
				AccessController.checkPermission(new AccessPermission(
						methodName));
				return null;
			}
		});
	}

	/**
	 * Returns a name of directory for SORCER data as specified in the SORCER
	 * provider configuration.
	 * 
	 * @return data directory name
	 */
	public File getDataDir() {
		return Sorcer.getDataDir();
	}

	/**
	 * Returns a directory for provider's scratch files containing service
	 * dataContext values.
	 * 
	 * @return a scratch directory
	 */
	public File getScratchDir() {
		return Sorcer.getNewScratchDir();
	}

	public File getScratchDir(String scratchDirNamePrefix) {
		return Sorcer.getNewScratchDir(scratchDirNamePrefix);
	}

	// adds scratch dir to dataContext
	public File getScratchDir(Context context, String scratchDirPrefix)
			throws ContextException, MalformedURLException {

		File scratchDir = getScratchDir(scratchDirPrefix);

		if (context.containsPath(SCRATCH_DIR_KEY)
				|| context.containsPath(SCRATCH_URL_KEY)) {
			// throw new ContextException(
			// "***error: dataContext already contains scratch dir or scratch url key; "
			// + "do not use this method twice on the same dataContext argument "
			// + "(use getScratchDir() and add scratch dir key and value "
			// + "yourself)");
			logger.warning("***warning: dataContext already contains scratch dir or scratch url key; "
					+ "beware of using this method twice on the same dataContext argument "
					+ "(using getScratchDir() and add scratch dir key and value "
					+ "yourself may be better)."
					+ "\n\tdataContext name = "
					+ context.getName() + "\n\tdataContext = " + context);
		}

		Contexts.putOutValue(context, SCRATCH_DIR_KEY,
				scratchDir.getAbsolutePath(),
				Sorcer.getProperty("engineering.provider.scratchdir"));

		Contexts.putOutValue(context, SCRATCH_URL_KEY,
				getScratchURL(scratchDir),
				Sorcer.getProperty("engineering.provider.scratchurl"));

		return scratchDir;
	}

	// adds scratch dir to dataContext
	public File getScratchDir(Context context) throws ContextException,
			MalformedURLException {
		return getScratchDir(context, "");
	}

	/**
	 * Deletes a direcory and all its files.
	 * 
	 * @param dir
	 *            to be deleted
	 * @return true if the directory is deleted
	 * @throws Exception
	 */
	public boolean deleteDir(File dir) throws Exception {
		return SorcerUtil.deleteDir(dir);
	}

	/**
	 * Returns the URL of a data HTTP server handling remote scratch files.
	 * 
	 * @param scratchFile
	 * @return a URL of the data HTTP server
	 * 
	 * @throws MalformedURLException
	 */
	public URL getScratchURL(File scratchFile) throws MalformedURLException {
		return Sorcer.getScratchURL(scratchFile);
	}

	/**
	 * Returns a service type of the provider served by this delegate as
	 * registered with lookup services.
	 * 
	 * @return a SorcerServiceType
	 */
	public SorcerServiceInfo getServiceType() {
		return serviceType;
	}

	public Properties getProviderProperties() {
		return config.getProviderProperties();
	}

	public Configuration getProviderConfiguration() {
		return config.getProviderConfiguraion();
	}

	public String getDescription() throws RemoteException {
		return config.getProperty(P_DESCRIPTION);
	}

	/**
	 * Set a name of the provider. The name may be defined in this provider's
	 * properties file.
	 */
	public void setProviderName(String name) throws RemoteException {
		config.setProviderName(name);
	}

	public String[] getGroups() throws RemoteException {
		return groupsToDiscover;
	}

	/**
	 * Adds an additional entry to add to the lookup attributes.
	 * 
	 * @param extra
	 *            the extra Lookup entry.
	 */
	public void addExtraLookupAttribute(Entry extra) {
		extraLookupAttributes.add(extra);
	}

	public List<Object> getProperties() {
		List<Object> allAttributes = new ArrayList<Object>();
		Entry[] attributes = getAttributes();
		for (Entry entry : attributes)
			allAttributes.add(entry);
		allAttributes.add(config.getProviderProperties());
		allAttributes.add(Sorcer.getProperties());
		return allAttributes;
	}

	/**
	 * Creates the service attributes to be used with Jini lookup services.
	 * <p>
	 * This function will create the following entries:
	 * <ul>
	 * <li>A {@link Name}.
	 * <li>A {@link SorcerServiceInfo}entry with all the information about this
	 * provider.
	 * <li>A main UIDescriptor if the provider overrides
	 * <li>Extra lookup attributes set via #addExtraLookupAttribute(Entry)
	 * {@link ServiceProvider#getMainUIDescriptor()}.
	 * </ul>
	 * 
	 * @return an array of Jini Service Entries.
	 * @throws ConfigurationException
	 */
	public Entry[] getAttributes() {
		final List<Entry> attrVec = new ArrayList<Entry>();

		try {
			// name of the provider suffixed in loadJiniConfiguration
			attrVec.add(new Name(getProviderName()));
			Entry sst = getSorcerServiceTypeEntry();
			attrVec.add(sst);
			// add additional entries declared in the Jini provider's
			// configuration
			Entry[] miscEntries = (Entry[]) config.jiniConfig.getEntry(
					ServiceProvider.PROVIDER, "entries", Entry[].class,
					new Entry[] {});
			for (int i = 0; i < miscEntries.length; i++) {
				attrVec.add(miscEntries[i]);
				// transfer location from entries if not defined in
				// SorcerServiceInfo
				if (miscEntries[i] instanceof Location
						&& ((SorcerServiceInfo) sst).location == null) {
					((SorcerServiceInfo) sst).location = "" + miscEntries[i];
				}
			}

			// add the service dataContext of this provider to provider attributes
			AccessControlContext context = AccessController.getContext();
			Subject subject = Subject.getSubject(context);
			// logger.finer("The subject in Provider Delegate is: " + subject);
		} catch (Exception ex) {
			logger.warning(SorcerUtil.stackTraceToString(ex));
		}

		// This construct may look strange. But it ensures that this class loads
		// if rio is not in the classpath
		//
		// The code is equivalent to
		// ApplianceInfo a = new ApplicaneInfo();
		// a.initialize();
		// attrVec.add(a);
		try {
			Class c;
			c = this.getClass().getClassLoader()
					.loadClass("org.jini.rio.entry.ApplianceInfo");
			Object appInfo = c.newInstance();
			Method m = c.getMethod("initialize", new Class[] {});
			m.invoke(appInfo, (Object[]) null);
			attrVec.add((Entry) appInfo);
		} catch (ClassNotFoundException e) {
			// This happens if RIO classes are not in classpath. Ignore
		} catch (InstantiationException e) {
			// This happens if RIO classes are not in classpath. Ignore
		} catch (IllegalAccessException e) {
			// This happens if RIO classes are not in classpath. Ignore
		} catch (SecurityException e) {
			// This happens if RIO classes are not in classpath. Ignore
		} catch (NoSuchMethodException e) {
			// This happens if RIO classes are not in classpath. Ignore
		} catch (IllegalArgumentException e) {
			// This happens if RIO classes are not in classpath. Ignore
		} catch (InvocationTargetException e) {
			// This happens if RIO classes are not in classpath. Ignore
		}

		attrVec.addAll(extraLookupAttributes);

		return (Entry[]) attrVec.toArray(new Entry[] {});
	}

	/**
	 * Creates an entry that is a {@link SorcerServiceInfo}.
	 * 
	 * @return an entry for the provider.
	 */
	private Entry getSorcerServiceTypeEntry() {
		SorcerServiceInfo serviceType = new SorcerServiceInfo();
		try {
			serviceType.providerName = config.getProviderName();
			serviceType.repository = config.getDataDir();
			serviceType.shortDescription = config.getProperty(P_DESCRIPTION);
			serviceType.location = config.getProperty(P_LOCATION);
			serviceType.groups = SorcerUtil.arrayToCSV(groupsToDiscover);
			serviceType.spaceGroup = spaceGroup;
			serviceType.spaceName = spaceName;
			serviceType.puller = spaceEnabled;
			serviceType.monitorable = monitorable;
			serviceType.matchInterfaceOnly = matchInterfaceOnly;
			serviceType.startDate = new Date().toString();
			serviceType.userDir = System.getProperty("user.dir");
			// serviceType.iGridHome = System.getProperty("iGrid.home");
			serviceType.userName = System.getProperty("user.name");
			serviceType.iconName = config.getIconName();

			if (publishedServiceTypes == null && spaceEnabled) {
				logger.severe(getProviderName()
						+ "does NOT declare its space interfaces");
				System.exit(1);
			}
			if (publishedServiceTypes != null) {
				String[] typeNames = new String[publishedServiceTypes.length];
				for (int i = 0; i < publishedServiceTypes.length; i++) {
					typeNames[i] = publishedServiceTypes[i].getName();
				}
				serviceType.publishedServices = typeNames;
			}
			serviceType.serviceID = provider.getProviderID();
		} catch (Exception ex) {
			logger.warning("Some problem in accessing attributes");
			logger.warning(SorcerUtil.stackTraceToString(ex));
		}
		String hostName = null, hostAddress = null;
		hostName = config.getProviderHostName();
		hostAddress = config.getProviderHostAddress();

		if (hostName != null) {
			serviceType.hostName = hostName;
		} else {
			logger.warning("Host is null!");
		}

		if (hostAddress != null) {
			serviceType.hostAddress = hostAddress;
			;
		} else {
			logger.warning("Host address is null!!");
		}
		return serviceType;
	}

	/**
	 * Restores the ServiceID from {@link SorcerConstants#S_SERVICE_ID_FILENAME}
	 * .
	 * <p>
	 * Please note: There is currently no method to save the ServiceID. So this
	 * method should probably be reworked.
	 */
	public void restore() {
		if (idPersistent) {
			try {
				// ObjectLogger.setResourceClass(this.getClass());
				this.setServerUuid((ServiceID) ObjectLogger.restore(Sorcer
						.getProperty(S_SERVICE_ID_FILENAME,
								Sorcer.getServiceIdFilename())));
			} catch (Exception e) { // first time if exception caught
				e.printStackTrace();
				return;
			}
		}
	}

	private void ensureServerUuidIsSet() {
		if (serverUuid == null) {
			serverUuid = UuidFactory.generate();
		}
	}

	/**
	 * Retrieves the ServerUUID as an ServiceID.
	 * 
	 * @return a ServiceID representation of the ServerUUID.
	 */
	public ServiceID getServiceID() {
		ensureServerUuidIsSet();
		return new ServiceID(this.serverUuid.getMostSignificantBits(),
				this.serverUuid.getLeastSignificantBits());
	}

	/**
	 * Retrieves the Unique ID of this server.
	 * 
	 * @return the {@link Uuid} of this server
	 */
	public Uuid getServerUuid() {
		ensureServerUuidIsSet();
		return serverUuid;
	}

	/**
	 * Sets the Uuid of this server from a given ServiceID.
	 * 
	 * @param serviceID
	 *            the ServiceID to use.
	 */
	public void setServerUuid(ServiceID serviceID) {
		logger.info("Setting service ID:" + serviceID);
		serverUuid = UuidFactory.create(serviceID.getMostSignificantBits(),
				serviceID.getLeastSignificantBits());
	}

	/**
	 * Sets the Uuid of this server from a given {@link Uuid}.
	 * 
	 * @param serverID
	 *            the Uuid to use.
	 */
	public void setServerUuid(Uuid serverID) {
		logger.info("Setting server ID:" + serverID);
		this.serverUuid = serverID;
	}

	public String getInfo() throws RemoteException {
		return provider.getInfo();
	}

	private void addType(Class type, Set typeSet, boolean withSupertypes) {
		if (type == null)
			return;
		String typeName = type.getName();
		if (typeSet.contains(typeName))
			return;

		typeSet.add(typeName);
		if (!withSupertypes)
			return;

		if (withSupertypes)
			addType(type.getSuperclass(), typeSet, withSupertypes);

		Class[] stypes = type.getInterfaces();
		for (int i = 0; i < stypes.length; i++) {
			addType(stypes[i], typeSet, withSupertypes);
		}
	}

	private Set getTypes() {
		SortedSet sortSet = new TreeSet();
		addType(provider.getClass(), sortSet, true);
		String proxyName = config.getProperty(P_PROXY_CLASS);
		// Util.debug(this, "getTypes:proxyName=" + proxyName);
		if (proxyName != null) {
			try {
				Class proxyClass = Class.forName(proxyName);
				addType(proxyClass, sortSet, true);
			} catch (ClassNotFoundException cnfe) {
				cnfe.printStackTrace();
			}
		}
		return sortSet;
	}

	static LeaseRenewalManager getLeaseManager() {
		return leaseManager;
	}

	public void destroy() throws RemoteException {
		if (spaceEnabled) {
			for (ExecutorService es : spaceHandlingPools)
				shutdownAndAwaitTermination(es);
			if (interfaceGroup != null) {
				Thread[] ifgThreads = new Thread[interfaceGroup.activeCount()];
				Thread[] ngThreads = new Thread[namedGroup.activeCount()];
				interfaceGroup.enumerate(ifgThreads);
				namedGroup.enumerate(ngThreads);
				// System.out.println("ifgThreads.length = " +
				// ifgThreads.length);
				// System.out.println("ngThreads.length = " + ngThreads.length);
				for (int i = 0; i < ifgThreads.length; i++) {
					// System.out.println("ifgThreads[" + i + "] = " +
					// ifgThreads[i]);
					ifgThreads[i].interrupt();
				}
				for (int i = 0; i < ngThreads.length; i++) {
					// System.out.println("ngThreads[" + i + "] = " +
					// ngThreads[i]);
					ngThreads[i].interrupt();
				}
			}
		}
		// stop delegates's KeepAwake thread
		running = false;
	}

	public void fireEvent() throws RemoteException {
		provider.fireEvent();
	}

	public boolean isValidTask(Exertion servicetask) throws RemoteException,
			ExertionException {
		if (!(servicetask instanceof NetTask)) {
			servicetask.getDataContext().reportException(
					new ExertionException(getProviderName()
							+ " received the exertion of unexpected type: "
							+ servicetask.getClass().getName()));
			return false;
		}
		NetTask task = (NetTask) servicetask;

		// if (task.subject == null)
		// throw new ExertionException("No subject provided with the task '" +
		// task.getName() + "'");
		// else if (!isAuthorized(task))
		// throw new ExertionException("The subject provided with the task '" +
		// task.getName() + "' not authorized to use the service '" +
		// providerName + "'");

		String pn = task.getProcessSignature().getProviderName();
		if (pn != null && !matchInterfaceOnly) {
			if (!(pn.equals(SorcerConstants.ANY) || SorcerConstants.ANY
					.equals(pn.trim()))) {
				if (!pn.equals(getProviderName())) {
					servicetask.getDataContext().reportException(
							new ExertionException(
									"No valid task for service provider: "
											+ config.getProviderName()));
					return false;
				}
			}
		}
		Class st = ((NetSignature) task.getProcessSignature()).getServiceType();

		if (publishedServiceTypes == null) {
			servicetask.getDataContext().reportException(
					new ExertionException(
							"No published interfaces defined by: "
									+ getProviderName()));
			return false;
		} else {
			for (int i = 0; i < publishedServiceTypes.length; i++) {
				if (publishedServiceTypes[i] == st) {
					return true;
				}
			}
		}
		servicetask.getDataContext().reportException(
				new ExertionException(
						"No valid task for published service types:\n"
								+ Arrays.toString(publishedServiceTypes)));
		return false;
	}

	private boolean isAuthorized(NetTask task) {
		Set principals = task.getSubject().getPrincipals();
		Iterator iterator = principals.iterator();
		Principal principal;
		while (iterator.hasNext()) {
			principal = (Principal) iterator.next();
			if (principal instanceof SorcerPrincipal) {
				if (principal.getName().equals("sobol"))
					return true;
				if (protocol == null)
					createProtocol();
				return ((ProxyProtocol) protocol).isAuthorized(
						(SorcerPrincipal) principal, ((NetSignature) task
								.getProcessSignature()).getServiceType(),
						config.getProviderName());
			}
		}
		return false;
	}

	/**
	 * Create GApp server proxy
	 */
	public void createProtocol() {
		String type = System.getProperty("portal.server");
		if (type != null && type.startsWith("http://"))
			protocol = new ServletProtocol(type);
		else
			protocol = new SocketProtocol(type);
	}

	protected void notify(Exertion task, int notificationType, String message)
			throws RemoteException {
		if (!notifying)
			return;
		logger.info(getClass().getName() + "::notify() START message:"
				+ message);
		try {

			MsgRef mr;
			SorcerNotifierProtocol notifier = (SorcerNotifierProtocol) ProviderAccessor
					.getNotifierProvider();

			mr = new MsgRef(((ServiceExertion) task).getId(), notificationType,
					config.getProviderName(), message,
					((ServiceExertion) task).getSessionId());
			// Util.debug(this, "::notify() RUNTIME SESSION ID:" +
			// task.getRuntimeSessionID());
			RemoteEvent re = new RemoteEvent(mr, eventID++, seqNum++, null);
			logger.info(getClass().getName() + "::notify() END.");
			notifier.notify(re);
		} catch (ClassNotFoundException cnfe) {
			cnfe.printStackTrace();
		}
	}

	public void notifyException(Exertion task, String message, Exception e,
			boolean fullStackTrace) throws RemoteException {

		if (message == null && e == null)
			message = "NO MESSAGE OR EXCEPTION PASSED";
		else if (message == null && e != null) {
			if (fullStackTrace)
				message = SorcerUtil.stackTraceToString(e);
			else
				message = e.getMessage();
		} else {
			if (fullStackTrace)
				message = message + " " + SorcerUtil.stackTraceToString(e);
			else
				message = message + " " + e.getMessage();
		}

		notify(task, NOTIFY_EXCEPTION, message);
	}

	public void notifyException(Exertion task, String message, Exception e)
			throws RemoteException {
		notifyException(task, message, e, false);
	}

	public void notifyExceptionWithStackTrace(Exertion task, Exception e)
			throws RemoteException {
		notifyException(task, null, e, true);
	}

	public void notifyException(Exertion task, Exception e)
			throws RemoteException {
		notifyException(task, null, e, false);
	}

	public void notifyInformation(Exertion task, String message)
			throws RemoteException {
		notify(task, NOTIFY_INFORMATION, message);
	}

	/*
	 * public void notifyFailure(ServiceTask task, Exception e, boolean
	 * fullStackTrace)throws RemoteException{ String message = null;
	 * if(fullStackTrace) message = Debug.stackTraceToString(e); else message =
	 * Debug.stackTraceToArray(e)[0];
	 * 
	 * notify(task, NOTIFY_WARNING, message); }
	 */

	public void notifyFailure(Exertion task, Exception e)
			throws RemoteException {
		notifyFailure(task, e.getMessage());
	}

	public void notifyFailure(Exertion task, String message)
			throws RemoteException {
		notify(task, NOTIFY_FAILURE, message);
	}

	public void notifyWarning(Exertion task, String message)
			throws RemoteException {
		notify(task, NOTIFY_WARNING, message);
	}

	/**
	 * Indicates the change of the monitored service dataContext.
	 * 
	 * @param sc
	 *            the service dataContext
	 * @throws MonitorException
	 * @throws RemoteException
	 */
	public void changed(Context sc, Object aspect) throws RemoteException,
			MonitorException {
		MonitoringSession session = ExertionSessionInfo.getSession();
		if (session != null)
			session.changed(sc, aspect);
	}

	// task/job monitoring API
	public void stop(UEID ueid, Subject subject)
			throws UnknownExertionException, AccessDeniedException {

		// if (ueid.sid == null || !ueid.sid.equals(serviceID))
		// throw new UnknownExertionException(" ServiceID does not match
		// corresponding to "+ueid.asString());

		synchronized (exertionStateTable) {
			if (exertionStateTable.get(ueid.exertionID) == null)
				throw new UnknownExertionException(
						" No exertion exists corresponding to "
								+ ueid.asString());

			exertionStateTable.put(ueid.exertionID, new Integer(
					ExecState.STOPPED));
		}
	}

	public boolean suspend(UEID ueid, Subject subject) throws RemoteException,
			UnknownExertionException {

		// if (ueid.sid == null || !ueid.sid.equals(serviceID))
		// throw new UnknownExertionException(" ServiceID does not match
		// corresponding to "+ueid.asString());

		synchronized (exertionStateTable) {
			if (exertionStateTable.get(ueid.exertionID) == null)
				throw new UnknownExertionException(
						" No exertion exists corresponding to "
								+ ueid.asString());

			exertionStateTable.put(ueid.exertionID, new Integer(
					ExecState.SUSPENDED));
		}

		return true;
	}

	/**
	 * @return Returns the provider config.
	 */
	public DeploymentConfiguration getProviderConfig() {
		return config;
	}

	/**
	 * @return Returns the provider Jini configuration instance.
	 */
	public Configuration getDeploymentConfig() {
		return config.jiniConfig;
	}

	/**
	 * Set the Jini Configuration for this provider delegate.
	 */
	public void setJiniConfig(Configuration config) {
		getProviderConfig().jiniConfig = config;
	}

	/**
	 * The configuration class for SORCER providers. This configuration collects
	 * the configuration settings for all SORCER service providers. It uses the
	 * provider properties file and/or Jini configuration file. The global
	 * environment properties are copied from this configuration to the
	 * {@link Sorcer} properties.
	 */
	public class DeploymentConfiguration {

		/** Properties found in provider.properties file */
		protected Properties props = new Properties();

		/** Jini Configuration */
		protected Configuration jiniConfig;

		/** Our data directory */
		protected String dataDir = null;

		/** Our data directory size in bytes */
		protected long dataLimit = 0;

		/**
		 * initializes this config object (loads all information).
		 * 
		 * @param exitOnEmptyName, propsFilename
		 */
		public void init(boolean exitOnEmptyName, String propsFilename) {
			// load configuration from a provider properties file
			if (propsFilename != null && propsFilename.length() > 0)
				loadConfiguration(propsFilename);
			// load configuration as defined in provider Jini configuration file
			// or as defined in SBP in relevant opstrings
			loadJiniConfiguration(jiniConfig);
			checkProviderName(exitOnEmptyName);
			fillInProviderHost();
			logger.info("*** provider properties from " + propsFilename + ": "
					+ GenericUtil.getPropertiesString(props));
		}

		public Configuration getProviderConfiguraion() {
			return jiniConfig;
		}

		/**
		 * @param exitOnEmptyName
		 */
		private void checkProviderName(boolean exitOnEmptyName) {
			String str;
			String providerName;

			// set provider name if defined in provider's properties
			str = getProperty(P_PROVIDER_NAME);
			if (str != null) {
				providerName = str.trim();
				if (!str.equals(providerName))
					props.setProperty(P_PROVIDER_NAME, providerName);
			} else {
				if (exitOnEmptyName) {
					logger.severe("Provider HALTED: its name not defined in the provider config file");
					System.exit(1);
				}
			}
		}

		/**
		 * 
		 */
		private void fillInProviderHost() {
			String hostname = null, hostaddress = null;
			try {
				hostname = InetAddress.getLocalHost().getHostName();
				if (hostname == null) {
					logger.warning("Could not aquire hostname");
					hostname = "[unknown]";
				} else {
					hostaddress = InetAddress.getLocalHost().getHostAddress();
				}
			} catch (Throwable t) {
				// Can be ignored.
			}

			props.put(P_PROVIDR_HOST, hostname);
			props.put(P_PROVIDR_ADDRESS, hostaddress);
		}

		private void extractDataDir() {
			try {
				dataDir = new File(".").getCanonicalPath() + File.separatorChar;
			} catch (IOException e) {
				e.printStackTrace();
			}
			String rootDir = Sorcer.getProperty(DOC_ROOT_DIR);
			String appDir = Sorcer.getProperty(P_DATA_DIR);

			if (rootDir == null || appDir == null)
				return;

			rootDir.replace('/', File.separatorChar);
			appDir.replace('/', File.separatorChar);

			if (!rootDir.endsWith(File.separator)) {
				rootDir += File.separator;
			}

			if (!appDir.endsWith(File.separator)) {
				appDir += File.separator;
			}

			dataDir = rootDir + appDir;
		}

		/**
		 * @return the directory where this provider should store its local
		 *         data.
		 */
		public String getDataDir() {
			if (dataDir == null)
				extractDataDir();

			return dataDir;
		}

		/**
		 * @return the directory where this provider should store its local
		 *         data.
		 */
		public long getDataLimit() {
			if (dataLimit == 0) {
				long limit = Long.parseLong(Sorcer.getProperty(P_DATA_LIMIT));
				dataLimit = limit;
			}
			return dataLimit;
		}

		/**
		 * Sets the provider name. Can be called manually if needed.
		 * 
		 * @param name
		 */
		public void setProviderName(String name) {
			props.setProperty(P_PROVIDER_NAME, name);
		}

		/**
		 * Sets a configuration property.
		 * 
		 * @param key
		 *            they key to set (usualy starts with provider.)
		 * @param value
		 *            the value to set to.
		 */
		public void setProperty(String key, String value) {
			props.setProperty(key, value);
		}

		/**
		 * Return a name of the provider. The name may be specified in this
		 * provider's properties file.
		 * 
		 * 
		 * @return the name of the provider
		 */
		public String getProviderName() {
			return getProperty(P_PROVIDER_NAME);
		}

		/**
		 * Return a file name of the provider's icon. The name may be specified
		 * in this provider's properties file.
		 * 
		 * @return the name of the provider
		 */
		public String getIconName() {
			return getProperty(P_ICON_NAME);
		}

		/**
		 * @return the host name for this provider
		 */
		public String getProviderHostName() {
			return getProperty(P_PROVIDR_HOST);
		}

		/**
		 * @return the host address for this provider
		 */
		public String getProviderHostAddress() {
			return getProperty(P_PROVIDR_ADDRESS);
		}

		/**
		 * Loads provider properties from a <code>filename</code> file. By
		 * default a provider loads its properties from
		 * <code>provider.properties</code> file located in the provider's
		 * package. Also, a provider properties file name can be specified as a
		 * variable <code>providerProperties</code> in a Jini configuration file
		 * for a SORCER provider. In this case the provider loads properties
		 * from the specified <code>providerProperties</code> file. Properties
		 * are available from the instance field <code>props</code> field and
		 * accessible calling the <code> getProperty(String)</code> method.
		 * 
		 * @param filename
		 *            the properties file name
		 * @see #getProperty
		 */
		public void loadConfiguration(String filename) {
            InputStream is = null;
            try {
				// check the class resource
				is = provider.getClass().getResourceAsStream(
						filename);
				// next check local resource
				if (is == null) {
					is = (InputStream) (new FileInputStream(new File(filename)));
				}

				if (is != null) {
					props = Sorcer.loadProperties(is);

					// copy loaded provider's properties to global Env
					// properties
					Sorcer.updateFromProperties(props);
				}
			} catch (Exception ex) {
				logger.warning("Not able to load provider's file properties"
						+ filename);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        //ignore
                    }
                }
            }
		}

		public Properties getProviderProperties() {
			return props;
		}

		/**
		 * Returns a value of a comma separated property as defined in. If the
		 * property value is not defined for the delegate's provider then the
		 * equivalent SORCR environment value value is returned.
		 * {@link SorcerConstants}.
		 * 
		 * @param key
		 *            a property (attribute)
		 * @return a property value
		 */
		public String getProperty(String key) {
			String val = props.getProperty(key);
			if (val != null)
				return val;
			else
				return Sorcer.getProperty(key);
		}

		public String getProperty(String property, String defaultValue) {
			String prop = getProperty(property);
			if (prop == null)
				return defaultValue;
			return prop;
		}

		public void updateFromProperties() {
			Sorcer.updateFromProperties(props);
		}

		/**
		 * Load the provider deployment configuration. The properties can be
		 * accessed calling getProperty() methods to obtain properties of this
		 * service provider. Also, the SORCER environment properties are updated
		 * by corresponding properties found in the provider's configuration and
		 * in the JVM system properties.
		 */
		private void loadJiniConfiguration(Configuration config) {
			String ev, val = null;

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_PROVIDER_NAME, String.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				setProviderName(val);

			String nameSuffixed = "";
			boolean globalNameSuffixed = Sorcer.nameSuffixed();
			try {
				nameSuffixed = (String) config.getEntry(
						ServiceProvider.PROVIDER, "nameSuffixed", String.class,
						"");
			} catch (ConfigurationException e1) {
				nameSuffixed = "";
			}
			// check for the specified suffix by the user
			String suffix = Sorcer.getNameSuffix();

			String suffixedName = null;
			if (nameSuffixed.length() == 0) {
				if (suffix == null)
					suffixedName = Sorcer.getSuffixedName(val);
				else
					suffixedName = val + "-" + suffix;
			} else if (!nameSuffixed.equals("true")
					&& !nameSuffixed.equals("false")) {
				suffixedName = val + "-" + nameSuffixed;
				nameSuffixed = "true";
			}
			// add provider name and SorcerServiceType entries
			// nameSuffixed not defined by this provider but in sorcer.env
			if (nameSuffixed.length() == 0 && globalNameSuffixed) {
				setProviderName(suffixedName);
			} else if (nameSuffixed.equals("true")) {
				setProviderName(suffixedName);
			}

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_DESCRIPTION, String.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_DESCRIPTION, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_LOCATION, String.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_LOCATION, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_TEMPLATE_MATCH, String.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_TEMPLATE_MATCH, val);

			try {
				val = ""
						+ (Boolean) jiniConfig.getEntry(
								ServiceProvider.PROVIDER,
								J_SERVICE_ID_PERSISTENT, boolean.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_SERVICE_ID_PERSISTENT, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_DATA_LIMIT, String.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_DATA_LIMIT, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_PORTAL_HOST, String.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_PORTAL_HOST, val);

			try {
				val = ""
						+ (Integer) jiniConfig.getEntry(
								ServiceProvider.PROVIDER, J_PORTAL_PORT,
								int.class);
			} catch (ConfigurationException e) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_PORTAL_PORT, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_WEBSTER_INTERFACE, String.class);
			} catch (ConfigurationException e5) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_WEBSTER_INTERFACE, val);

			try {
				val = ""
						+ (Integer) jiniConfig.getEntry(
								ServiceProvider.PROVIDER, J_WEBSTER_PORT,
								int.class);
			} catch (ConfigurationException e4) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_WEBSTER_PORT, val);

			try {
				val = SorcerUtil.arrayToCSV((String[]) jiniConfig.getEntry(
						ServiceProvider.PROVIDER, J_GROUPS, String[].class));
			} catch (ConfigurationException e3) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_GROUPS, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_SPACE_GROUP, String.class);
			} catch (ConfigurationException e2) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_SPACE_GROUP, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_SPACE_NAME, String.class);
			} catch (ConfigurationException e2) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_SPACE_NAME, val);

			try {
				val = SorcerUtil.arrayToCSV((String[]) jiniConfig.getEntry(
						ServiceProvider.PROVIDER, J_LOCATORS, String[].class));
			} catch (ConfigurationException e) {
				val = null;
			}

			// if not defined in provider deployment file use from sorcer.env
			if ((val == null) || (val.length() == 0))
				val = Sorcer.getProperty(P_LOCATORS);

			if ((val != null) && (val.length() > 0))
				props.put(P_LOCATORS, val);

			try {
				val = (String) jiniConfig.getEntry(ServiceProvider.PROVIDER,
						J_ICON_NAME, String.class);
			} catch (ConfigurationException e5) {
				val = null;
			}
			if ((val != null) && (val.length() > 0))
				props.put(P_ICON_NAME, val);

			// update and log Sorcer properties
			Sorcer.updateFromProperties(props);
			Sorcer.updateFromProperties(System.getProperties());
			Properties envProps = Sorcer.getEnvProperties();
			logger.finer("All SORCER updated properties: " + envProps);
		}
	}

	public String getProviderName() {
		return config.getProviderName();
		// return provider.getProviderName();
	}

	public Provider getProvider() {
		return provider;
	}

	public boolean mutualExlusion() {
		return mutualExclusion;
	}

	public void mutualExlusion(boolean mutualExlusion) {
		this.mutualExclusion = mutualExlusion;
	}

	public boolean isSpaceTransactional() {
		return workerTransactional;
	}

	public TrustVerifier getProxyVerifier() {
		if (smartProxy == null)
			return new ProxyVerifier(outerProxy, this.getServerUuid());
		else
			return new ProxyVerifier(smartProxy, this.getServerUuid());
	}

	/**
	 * Returns an object that implements whatever administration interfaces are
	 * appropriate for the particular service.
	 * 
	 * @return an object that implements whatever administration interfaces are
	 *         appropriate for the particular service.
	 */
	public Object getAdmin() {
		return adminProxy;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see sorcer.core.provider.OuterProxy#setAdmin(java.lang.Object)
	 */
	public void setAdmin(Object proxy) {
		adminProxy = (Remote) proxy;
	}

	/**
	 * Unexports the services of this provider appropriately.
	 * 
	 * @param force
	 *            terminate in progress calls if necessary
	 * @return true if unexport succeeds
	 */
	public boolean unexport(boolean force) throws NoSuchObjectException {
		boolean success = true;
		if (outerExporter != null) {
			exports.remove(outerProxy);
			success &= outerExporter.unexport(force);
			outerExporter = null;
			outerProxy = null;
		}

		if (partnerExporter != null) {
			exports.remove(innerProxy);
			success &= partnerExporter.unexport(force);
			outerProxy = null;
			partnerExporter = null;
		}

		if (serviceBeans != null && serviceBeans.length > 0) {
			for (int i = 0; i < serviceBeans.length; i++) {
				exports.remove(serviceBeans[i]);
			}
		}

		return success;
	}

	/**
	 * Returns a proxy object for this provider. If the smart proxy is alocated
	 * then returns a non exported object to be registerd with loookup services.
	 * However, if a smart proxy implements {@link sorcer.core.provider.proxy.Outer} then the
	 * provider's proxy is set as its inner proxy. Otherwise the {@link Remote}
	 * outer proxy of this provider is returned.
	 * 
	 * @return a proxy, or null
	 * @see sorcer.core.Provider#getProxy()
	 */
	public Object getProxy() {
		try {
			if (smartProxy == null) {
				if (innerProxy != null && partner == null
						&& outerProxy instanceof Partnership) {
					((Partnership) outerProxy).setInner(innerProxy);
					((Partnership) outerProxy).setAdmin(adminProxy);
				} else if (partner != null && partner instanceof Partnership) {
					((Partnership) partner).setInner(innerProxy);
					((Partnership) partner).setAdmin(adminProxy);
				}
				return outerProxy;
			} else if (smartProxy instanceof Partnership) {
				((Partnership) smartProxy).setInner(outerProxy);
				((Partnership) smartProxy).setAdmin(adminProxy);
			}
			return smartProxy;
		} catch (ProviderException e) {
			return null;
		}
	}

	/** {@inheritDoc} */
	public Remote getInner() {
		return (Remote) innerProxy;
	}

	/** {@inheritDoc} */
	public void setInner(Object innerProxy) throws ProviderException {
		if (outerProxy instanceof Partnership)
			((Partnership) outerProxy).setInner(innerProxy);
		else
			throw new ProviderException("wrong inner proxy for this provider");
	}

	/**
	 * Returns the exporter to use to export this server.
	 * <p>
	 * Two ways for a client to expose his service:
	 * <ol>
	 * <li>Directly subclass ServiceProvider in which case, configuration should
	 * provide the following: <br>
	 * <code>exporter = xxx //Object exported will be this object</code><br>
	 * By default BasicJeriExporter is used
	 * 
	 * <li>Expose objects as services <br>
	 * <code>beans = new String[] { ..... }<br>
	 *    proxyName = "xxx.xxx"</code><br>
	 * Provide the proxy name and have a constructor with one argument, which
	 * accepts the exported inner proxy.
	 * </ol>
	 * 
	 * @param config
	 *            the configuration to use for supplying the exporter
	 * @return the exporter to use to export this server
	 * @throws ConfigurationException
	 *             if a problem occurs retrieving entries from the configuration
	 */
	@SuppressWarnings("unchecked")
	private void getExporters(Configuration config) {
		try {
			String exporterInterface = Sorcer.getProperty(P_EXPORTER_INTERFACE);
			try {
				exporterInterface = (String) config.getEntry(
						ServiceProvider.COMPONENT, EXPORTER_INTERFACE,
						String.class, null);
			} catch (Exception e) {
				// do nothng
			}
			logger.info(">>>>> exporterInterface: " + exporterInterface);

			int exporterPort = 0;
			String port = Sorcer.getProperty(P_EXPORTER_PORT);
			if (port != null)
				exporterPort = Integer.parseInt(port);
			try {
				exporterPort = (Integer) config.getEntry(
						ServiceProvider.COMPONENT, EXPORTER_PORT,
						Integer.class, null);
			} catch (Exception e) {
				// do nothng
			}
			logger.info(">>>>> exporterPort: " + exporterPort);

			try {
				// initialize smart proxy
				smartProxy = config.getEntry(ServiceProvider.COMPONENT,
						SMART_PROXY, Object.class, null);
			} catch (Exception e) {
				logger.info(">>>>> NO SMART PROXY specified");
				logger.throwing(this.getClass().getName(), "getExporters", e);
				smartProxy = null;
			}

			List<Object> allBeans = new ArrayList<Object>();
			// find it out if service bean instances are available
			Object[] beans = (Object[]) Config.getNonNullEntry(config,
					ServiceProvider.COMPONENT, BEANS, Object[].class,
					new Object[] {});
			if (beans.length > 0) {
				logger.finer("*** service beans by " + getProviderName()
						+ "\nfor: " + Arrays.toString(beans));
				for (int i = 0; i < beans.length; i++) {
					allBeans.add(beans[i]);
					exports.put(beans[i], this);
				}
			}

			// find it out if data service bean instances are available
			Object[] dataBeans = (Object[]) Config.getNonNullEntry(config,
					ServiceProvider.COMPONENT, DATA_BEANS, Object[].class,
					new Object[] {}, getProviderProperties());
			if (dataBeans.length > 0) {
				logger.finer("*** data service beans by " + getProviderName()
						+ "\nfor: " + Arrays.toString(dataBeans));
				for (int i = 0; i < dataBeans.length; i++) {
					allBeans.add(dataBeans[i]);
					exports.put(dataBeans[i], this);
				}
			}

			// find it out if service classes are available
			Class[] beanClasses = (Class[]) Config.getNonNullEntry(config,
					ServiceProvider.COMPONENT, BEAN_CLASSES, Class[].class,
					new Class[] {});
			if (beanClasses.length > 0) {
				logger.finer("*** service bean classes by " + getProviderName()
						+ " for: \n" + Arrays.toString(beanClasses));
				for (int i = 0; i < beanClasses.length; i++)
					allBeans.add(instantiate(beanClasses[i]));
			}

			// find it out if Groovy scripts are available
			String[] scriptlets = (String[]) Config.getNonNullEntry(config,
					ServiceProvider.COMPONENT, SCRIPTLETS, String[].class,
					new String[] {});
			if (scriptlets.length > 0) {
				logger.finer("*** service scriptlets by " + getProviderName()
						+ " for: \n" + Arrays.toString(scriptlets));
				for (int i = 0; i < scriptlets.length; i++)
					allBeans.add(instantiateScriplet(scriptlets[i]));
			}

			if (allBeans.size() > 0) {
				logger.finer("*** all beans by " + getProviderName()
						+ " for: \n" + allBeans);
				serviceBeans = allBeans.toArray();
				initServiceBeans(serviceBeans);
				ilFactory = new SorcerILFactory(serviceComponents,
						implClassLoader);
				outerExporter = new BasicJeriExporter(
						TcpServerEndpoint.getInstance(exporterInterface,
								exporterPort), ilFactory);
			} else {
				logger.finer("*** NO beans used by " + getProviderName());
				outerExporter = (Exporter) Config.getNonNullEntry(
						config,
						ServiceProvider.COMPONENT,
						EXPORTER,
						Exporter.class,
						new BasicJeriExporter(TcpServerEndpoint.getInstance(
								exporterInterface, exporterPort),
								new BasicILFactory()));
				if (outerExporter == null) {
					logger.warning("*** NO provider exporter defined!!!");
				} else {
					logger.finer("current exporter: "
							+ outerExporter.toString());
				}

				partnerExporter = (Exporter) Config.getNonNullEntry(config,
						ServiceProvider.COMPONENT, SERVER_EXPORTER,
						Exporter.class);
				if (partnerExporter == null) {
					logger.warning("NO provider inner exporter defined!!!");
				} else {
					logger.finer("your partner exporter: " + partnerExporter);
				}
			}
		} catch (Exception ex) {
			// ignore missing exporters and use default configurations for exporters
		}
	}

	/**
	 * Initializes the map between all the interfaces and the service object
	 * passed via the configuration file.
	 * 
	 * @param serviceBeans
	 *            service objects exposing their interface types
	 */
	@SuppressWarnings("unchecked")
	private Map initServiceBeans(Object[] serviceBeans) {
		if (serviceBeans == null)
			try {
				throw new NullPointerException("No service beans defined by: "
						+ provider.getProviderName());
			} catch (RemoteException e) {
				// ignore it
			}
		serviceComponents = new Hashtable();

		for (int i = 0; i < serviceBeans.length; i++) {
			Class[] interfaze = ((Object) serviceBeans[i]).getClass()
					.getInterfaces();
			for (int j = 0; j < interfaze.length; j++) {
				// if (interfaze[j].getDeclaredMethods().length != 0)
				// allow marker interfaces to be added
				serviceComponents.put(interfaze[j], serviceBeans[i]);
			}
		}
		return serviceComponents;
	}

	private Object instantiateScriplet(String scripletFilename)
			throws Exception {
		String[] tokens = SorcerUtil.tokenize(scripletFilename, "|");
		Object bean = null;
		Object configurator = null;
		GroovyShell shell = null;
		bean = shell.evaluate(new File(tokens[0]));
		for (int i = 1; i < tokens.length; i++) {
			configurator = shell.evaluate(new File(tokens[i]));
			if ((configurator instanceof Configurator)
					&& (bean instanceof Configurable)) {
				shell.setVariable("configurable", bean);
				bean = ((Configurator) configurator).configure();
			}
		}
		initBean(bean);
		return bean;
	}

	private Object instantiate(Class beanClass) throws Exception {
		return createBean(beanClass);
	}

	private Object instantiate(String serviceBean) throws Exception {
		Class clazz = Class.forName(serviceBean, false, implClassLoader);
		return createBean(clazz);
	}

	private Object createBean(Class beanClass) throws Exception {
		Object bean = beanClass.newInstance();
		initBean(bean);
		return bean;
	}

	private Object initBean(Object serviceBean) {
		try {
			java.lang.reflect.Method m = serviceBean.getClass().getMethod(
					"init", new Class[] { Provider.class });
			m.invoke(serviceBean, new Object[] { provider });
		} catch (Exception e) {
			logger.log(Level.INFO, "No 'init' method for this service bean: "
					+ serviceBean.getClass().getName());
		}
		exports.put(serviceBean, this);
		logger.fine(">>>>>>>>>>> exported service bean: \n" + serviceBean
				+ "\n by provider: " + provider);
		return serviceBean;
	}

	/**
	 * Returns a partner service specified in the provider's Jini configuration.
	 * 
	 * @param partnerName
	 *            name of the partner service
	 * @param partnerType
	 *            service type (interface) of the partner service
	 * @throws ExportException
	 */
	private Remote getPartner(String partnerName, Class partnerType)
			throws ExportException {
		// get the partner and its proxy
		// if it is exportable, export it, otherwise discover one
		Remote pp = null;
		if (partner == null) {
			if (partnerType != null) {
				// Class clazz = null;
				// if (partnerExporter != null) {
				// // get the partner instance
				// try {
				// clazz = Class.forName(partnerType);
				// } catch (ClassNotFoundException e) {
				// try {
				// String codebase = System
				// .getProperty("java.rmi.server.codebase");
				// logger.info(">>>> partner codebase: "
				// + codebase);
				//
				// String[] urlNames = SorcerUtil
				// .tokenize(codebase, " ");
				// URL[] urls = new URL[urlNames.length];
				// for (int i = 0; i < urlNames.length; i++)
				// urls[i] = new URL(urlNames[i]);
				//
				// ClassLoader loader = new URLClassLoader(urls);
				// clazz = Class.forName(partnerType, false,
				// loader);
				// } catch (MalformedURLException e1) {
				// logger.throwing(
				// ServiceAccessor.class.getName(),
				// "getService", e1);
				// clazz = null;
				// } catch (ClassNotFoundException e2) {
				// logger.throwing(
				// ServiceAccessor.class.getName(),
				// "getService", e2);
				// clazz = null;
				// }
				// }
				try {
					partner = (Remote) partnerType.newInstance();
				} catch (InstantiationException e) {
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				}
				// if partner exported use it as the primary proxy
				if (partner != null) {
					pp = partnerExporter.export((Remote) partner);
					if (pp != null) {
						innerProxy = outerProxy;
						outerProxy = pp;
					}
				}
			} else {
				// if partner discovered use it as the inner proxy
				innerProxy = (Remote) ProviderAccessor.getProvider(partnerName,
						partnerType);
			}
		} else {
			// if partner exported use it as the primary proxy
			if (partner != null) {
				if (partnerExporter == null)
					partnerExporter = new BasicJeriExporter(
							TcpServerEndpoint.getInstance(0),
							new BasicILFactory());
				pp = partnerExporter.export(partner);
				if (pp != null) {
					innerProxy = outerProxy;
					outerProxy = pp;
				} else
					// use partner as this provider's inner proxy
					innerProxy = partner;
			}
			logger.info(">>>>> got innerProxy: " + innerProxy + "\nfor: "
					+ partner + "\nusing exporter: " + partnerExporter);
		}
		return partner;
	}

	public static String[] toArray(String arg) {
		StringTokenizer token = new StringTokenizer(arg, " ,;");
		String[] array = new String[token.countTokens()];
		int i = 0;
		while (token.hasMoreTokens()) {
			array[i] = token.nextToken();
			i++;
		}
		return (array);
	}

	public Object getSmartProxy() {
		return smartProxy;
	}

	public Remote getPartner() {
		return partner;

	}

	public Remote getProviderProxy() {
		return providerProxy;
	}

	public boolean isSpaceSecurityEnabled() {
		return spaceSecurityEnabled;
	}

	public Map getServiceComponents() {
		return serviceComponents;
	}

	public void setServiceComponents(Map serviceComponents) {
		this.serviceComponents = serviceComponents;
	}

	public Logger getContextLogger() {
		return contextLogger;
	}

	public Logger getProviderLogger() {
		return providerLogger;
	}

	public Logger getRemoteLogger() {
		return remoteLogger;
	}

    private void initContextLogger() {
        Handler h = null;
        try {
            contextLogger = Logger.getLogger(PRIVATE_CONTEXT_LOGGER + "."
                    + getProviderName());

            String sorcerpath = System.getenv("SORCER_HOME");
            if (sorcerpath == null) {
                sorcerpath = System.getProperty(SORCER_HOME);
            }
            h = new FileHandler(sorcerpath + "/logs/remote/dataContext-"
                    + getProviderName() + "-" + getHostName() + "-cxt%g.log",
                    20000, 8, true);
            if (h != null) {
                h.setFormatter(new SimpleFormatter());
                contextLogger.addHandler(h);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initProviderLogger() {
        Handler h = null;
        try {
            providerLogger = Logger.getLogger(PRIVATE_PROVIDER_LOGGER + "."
                    + getProviderName());
            String igridpath = System.getenv("SORCER_HOME");
            if (igridpath == null) {
                igridpath = System.getProperty(SORCER_HOME);
            }
            h = new FileHandler(igridpath + "/logs/remote/provider-"
                    + getProviderName() + "-" + getHostName() + "-prv%g.log",
                    20000, 8, true);
            if (h != null) {
                h.setFormatter(new SimpleFormatter());
                providerLogger.addHandler(h);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


	private void initRemoteLogger(Level level, String managerName,
			String loggerName) {
		Handler rh = null;
		try {
			remoteLogger = Logger.getLogger(loggerName);
			rh = new RemoteHandler(level, managerName);
			if (remoteLogger != null && rh != null) {
				rh.setFormatter(new SimpleFormatter());
				remoteLogger.addHandler(rh);
				remoteLogger.setUseParentHandlers(false);
			}
		} catch (SecurityException e) {
			e.printStackTrace();
		}
	}

	public String getHostAddress() {
		if (hostAddress == null)
			try {
				hostAddress = InetAddress.getLocalHost().getHostAddress();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		return hostAddress;
	}

	public String getHostName() {
		if (hostName == null) {
			try {
				hostName = InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}
		return hostName;
	}

	public boolean isMonitorable() {
		return monitorable;
	}

	public List<ExecutorService> getSpaceHandlingPools() {
		return spaceHandlingPools;
	}

	void shutdownAndAwaitTermination(ExecutorService pool) {
		pool.shutdown(); // Disable new tasks from being submitted
		try {
			// Wait a while for existing tasks to terminate
			if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
				pool.shutdownNow(); // Cancel currently executing tasks
				// Wait a while for tasks to respond to being cancelled
				if (!pool.awaitTermination(3, TimeUnit.SECONDS))
					System.err.println("Pool did not terminate");
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			pool.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * <p>
	 * Returns a dataContext manager of this delegate as defined in the Jini
	 * configuration file.
	 * </p>
	 * 
	 * @return the contextManager
	 */
	public ContextManagement getContextManager() {
		return contextManager;
	}

	public Class[] getPublishedServiceTypes() {
		return publishedServiceTypes;
	}

	public final static String EXPORTER = "exporter";

	public final static String BEANS = "beans";

	public final static String DATA_BEANS = "dataBeans";

	public final static String SCRIPTLETS = "scriptlets";

	public final static String BEAN_CLASSES = "beanClasses";

	public final static String CONTEXT_MANAGER = "contextManager";

	public final static String SMART_PROXY = "smartProxy";

	public final static String SERVER = "server";

	public final static String SERVER_TYPE = "serverType";

	public final static String REMOTE_LOGGING = "remoteLogging";

	public final static String REMOTE_LOGGER_MANAGER_NAME = "remoteLoggerManagerName";

	public final static String REMOTE_LOGGER_NAME = "remoteLoggerName";

	public final static String REMOTE_LOGGER_LEVEL = "remoteLoggerLevel";

	public final static String REMOTE_CONTEXT_LOGGING = "remoteContextLogging";

	public final static String REMOTE_PROVIDER_LOGGING = "remoteProviderLogging";

	public final static String PROVIDER_MONITORING = "monitorEnabled";

	public final static String PROVIDER_NOTIFYING = "notifierEnabled";

	public final static String SERVER_NAME = "serverName";

	public final static String SERVER_EXPORTER = "serverExporter";

	public final static String EXPORTER_INTERFACE = "exporterInterface";

	public final static String EXPORTER_PORT = "exporterPort";

	public final static int KEEP_ALIVE_TIME = 1000;

	public static final String SPACE_ENABLED = "spaceEnabled";

	public static final String SPACE_READINESS = "spaceReadiness";

	public static final String MUTUAL_EXCLUSION = "mutualExclusion";

	public static final String SPACE_SECURITY_ENABLED = "spaceSecurityEnabled";

	public static final String WORKER_TRANSACTIONAL = "workerTransactional";

	public static final String WORKER_COUNT = "workerCount";

	public static final String WORKER_PER_INTERFACE_COUNT = "workerPerInterfaceCount";

	public static final String SPACE_WORKER_QUEUE_SIZE = "workerQueueSize";

	public static final String MAX_WORKER_POOL_SIZE = "maxWorkerPoolSize";

	public static final String WORKER_TRANSACTION_LEASE_TIME = "workerTransactionLeaseTime";

	public static final String SPACE_TIMEOUT = "workerTimeout";

	public static final String INTERFACE_ONLY = "matchInterfaceOnly";

}
