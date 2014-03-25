/**
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
package sorcer.boot;

import com.google.inject.*;
import com.google.inject.Inject;
import com.google.inject.name.Names;
import com.sun.jini.start.AggregatePolicyProvider;
import com.sun.jini.start.LifeCycle;
import com.sun.jini.start.ServiceDescriptor;
import net.jini.admin.Administrable;
import net.jini.config.Configuration;
import net.jini.config.ConfigurationException;
import net.jini.config.ConfigurationProvider;
import net.jini.config.EmptyConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AbstractFileFilter;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.rioproject.impl.opstring.OpStringLoader;
import org.rioproject.opstring.OperationalString;
import org.rioproject.opstring.ServiceElement;
import org.rioproject.resolver.Artifact;
import org.rioproject.resolver.Resolver;
import org.rioproject.resolver.ResolverException;
import org.rioproject.resolver.ResolverHelper;
import org.rioproject.start.RioServiceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sorcer.boot.platform.PlatformLoader;
import sorcer.boot.util.JarClassPathHelper;
import sorcer.boot.util.ServiceDescriptorProcessor;
import sorcer.core.DestroyAdmin;
import sorcer.core.SorcerEnv;
import sorcer.protocol.ProtocolHandlerRegistry;
import sorcer.provider.boot.AbstractServiceDescriptor;
import sorcer.util.IOUtils;
import sorcer.util.JavaSystemProperties;

import javax.inject.*;
import javax.inject.Provider;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.rmi.RemoteException;
import java.security.Policy;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.*;

import static sorcer.core.SorcerConstants.E_RIO_HOME;
import static sorcer.provider.boot.AbstractServiceDescriptor.Service;
import static sorcer.resolver.Resolver.resolveAbsolute;

/**
 * @author Rafał Krupiński
 */
public class ServiceStarter implements LifeCycle {
    final private static Logger log = LoggerFactory.getLogger(ServiceStarter.class);
    final private static String START_PACKAGE = "com.sun.jini.start";

    private final Deque<Service> services = new LinkedList<Service>();

    //just to keep the references
    private final Set<Service> nonDestroyServices = new HashSet<Service>();

    private volatile boolean bootInterrupted;

    private final LifeCycle exitMonitor;
    protected Injector injector;
    private PlatformLoader platformLoader;

    public ServiceStarter(LifeCycle exitMonitor) {
        this.exitMonitor = exitMonitor;
    }

    /**
     * Start services from the configs
     *
     * @param configs file path or URL of the services.config configuration
     */
    public void start(Collection<String> configs) throws Exception {
        log.info("******* Starting Sorcersoft.com SORCER *******");

        Injector rootInjector = createInjector();

        File rioHome = getRioHome();
        File rioPlatform = new File(rioHome, "config/platform");
        File sorcerPlatform = new File(rioPlatform, "service");
        platformLoader = new PlatformLoader(rootInjector, rioPlatform, sorcerPlatform);
        platformLoader.create();
        injector = platformLoader.getInjector();

        log.debug("Starting from {}", configs);

        List<String> riverServices = new LinkedList<String>();
        List<File> cfgJars = new LinkedList<File>();
        List<File> opstrings = new LinkedList<File>();

        for (String path : configs) {
            File file = null;
            if (path.startsWith(":")) {
                file = findArtifact(path.substring(1));
            } else if (Artifact.isArtifact(path))
                file = new File(resolveAbsolute(path));
            if (file == null) file = new File(path);

            IOUtils.ensureFile(file, IOUtils.FileCheck.readable);
            path = file.getPath();
            String ext = path.substring(path.lastIndexOf('.') + 1);

            if ("config".equals(ext))
                riverServices.add(path);
            else if ("oar".equals(ext) || "jar".equals(ext))
                cfgJars.add(file);
            else if ("opstring".equals(ext) || "groovy".equals(ext))
                opstrings.add(file);
            else
                throw new IllegalArgumentException("Unrecognized file " + path);
        }
        Map<Configuration, Collection<? extends ServiceDescriptor>> descs = new LinkedHashMap<Configuration, Collection<? extends ServiceDescriptor>>();
        descs.putAll(instantiateDescriptors(riverServices));

        List<OpstringServiceDescriptor> serviceDescriptors = createFromOpStrFiles(opstrings);
        serviceDescriptors.addAll(createFromOar(cfgJars));
        descs.put(EmptyConfiguration.INSTANCE, serviceDescriptors);

        instantiateServices(descs);
        log.debug("*** Sorcersoft.com SORCER started ***");
    }

    private Injector createInjector() {
        return Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Resolver.class).toProvider(new Provider<Resolver>() {
                    @Override
                    public Resolver get() {
                        try {
                            return ResolverHelper.getResolver();
                        } catch (ResolverException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }).in(Scopes.SINGLETON);
                bind(ProtocolHandlerRegistry.class).toInstance(ProtocolHandlerRegistry.get());
                bind(Policy.class).annotatedWith(Names.named("initialGlobalPolicy")).toInstance(Policy.getPolicy());
                bind(AggregatePolicyProvider.class).annotatedWith(Names.named("globalPolicy")).toProvider(new Provider<AggregatePolicyProvider>() {
                    @Inject
                    @Named("initialGlobalPolicy")
                    Policy initialGlobalPolicy;

                    @Override
                    public AggregatePolicyProvider get() {
                        AggregatePolicyProvider globalPolicy = new AggregatePolicyProvider(initialGlobalPolicy);
                        Policy.setPolicy(globalPolicy);
                        log.debug("Global policy set: {}",
                                globalPolicy);
                        return globalPolicy;
                    }
                }).in(Scopes.SINGLETON);
                bind(JarClassPathHelper.class).in(Scopes.SINGLETON);
            }
        });
    }

    protected File getRioHome() {
        String rioHomePath = System.getProperty(E_RIO_HOME, System.getenv(E_RIO_HOME));
        if (rioHomePath == null)
            throw new IllegalStateException("No RIO_HOME defined, no platform");
        return new File(rioHomePath);
    }

    public void stop() {
        log.debug("*** Stopping Sorcersoft.com SORCER ***");

        bootInterrupted = true;
        Service service;
        while ((service = services.pollLast()) != null)
            stop(service);

        log.info("******* Sorcersoft.com SORCER stopped *******");
        exitSorcer();
    }

    private void stop(Service service) {
        if (service.destroyer != null) {
            log.info("Stopping {}", service.impl);
            service.destroyer.destroy();
        } else {
            log.debug("Unable to stop {}", service.impl);
        }
    }

    private void exitSorcer() {
        exitMonitor.unregister(this);
    }

    protected ServiceDestroyer getDestroyer(Object service) {
        if (service instanceof DestroyAdmin) {
            return new SorcerServiceDestroyer((DestroyAdmin) service);
        } else if (service instanceof com.sun.jini.admin.DestroyAdmin) {
            return new RiverServiceDestroyer((com.sun.jini.admin.DestroyAdmin) service);
        } else if (service instanceof Administrable)
            try {
                return getDestroyer(((Administrable) service).getAdmin());
            } catch (RemoteException e) {
                log.warn("Error while calling local object {}", service, e);
            }
        return null;
    }

    @Override
    public boolean unregister(Object impl) {
        List<Service> copy;
        synchronized (services) {
            copy = new ArrayList<Service>(services);
        }
        boolean result = false;
        for (Service service : copy) {
            if (service.impl == impl) {
                synchronized (services) {
                    services.remove(service);
                }
                result = true;
            }
        }
        if (result) {
            boolean exit;
            synchronized (services) {
                if (log.isInfoEnabled())
                    log.debug("Service count: {}", services.size());
                exit = services.isEmpty();
            }
            if (exit) {
                log.info("No services left; shutting down SORCER");
                exitSorcer();
            }
        }
        return result;
    }

    private File findArtifact(String artifactId) throws IOException {
        File homeDir = SorcerEnv.getHomeDir().getCanonicalFile();
        File userDir = new File(System.getProperty(JavaSystemProperties.USER_DIR)).getCanonicalFile();

        Collection<File> roots = new LinkedList<File>();
        String homePath = homeDir.getPath();
        String userPath = userDir.getPath();

        //if one directory is ancestor of another, use only the ancestor
        if (homeDir.equals(userDir) || homePath.startsWith(userPath))
            roots.add(userDir);
        else if (userPath.startsWith(homePath))
            roots.add(homeDir);
        else {
            roots.add(userDir);
            roots.add(homeDir);
        }
        //roots.add(new File(SorcerEnv.getRepoDir()));

        File result = null;
        for (File root : roots) {
            Collection<File> files = FileUtils.listFiles(root, new ArtifactIdFileFilter(artifactId), DirectoryFileFilter.INSTANCE);
            if (files.size() > 0) {
                result = files.iterator().next();
                if (files.size() > 1) {
                    log.warn("Found {} files in {} possibly matching artifactId, using {}", files.size(), root, result);
                    log.debug("Files found: {}", files);
                }
                break;
            }
        }
        return result;
    }

    protected List<OpstringServiceDescriptor> createFromOpStrFiles(Collection<File> files) throws Exception {
        List<OpstringServiceDescriptor> result = new LinkedList<OpstringServiceDescriptor>();
        String policyFile = System.getProperty(JavaSystemProperties.SECURITY_POLICY);
        URL policyFileUrl = new File(policyFile).toURI().toURL();
        OpStringLoader loader = new OpStringLoader();
        for (File opString : files) {
            try {
                OperationalString[] operationalStrings = loader.parseOperationalString(opString);
                result.addAll(createServiceDescriptors(operationalStrings, policyFileUrl));
            } catch (Exception x) {
                log.warn("Could not parse Operational String {}", opString, x);
            } catch (NoClassDefFoundError x) {
                log.warn("Could not parse Operational String {}", opString, x);
                throw x;
            }
        }
        return result;
    }

    private List<OpstringServiceDescriptor> createFromOar(Iterable<File> oarFiles) throws Exception {
        List<OpstringServiceDescriptor> result = new LinkedList<OpstringServiceDescriptor>();
        for (File oarFile : oarFiles) {
            SorcerOAR oar = new SorcerOAR(oarFile);
            OperationalString[] operationalStrings = oar.loadOperationalStrings();
            URL policyFile = oar.getPolicyFile();
            result.addAll(createServiceDescriptors(operationalStrings, policyFile));
        }
        return result;
    }

    private List<OpstringServiceDescriptor> createServiceDescriptors(OperationalString[] operationalStrings, URL policyFile) throws ConfigurationException {
        List<OpstringServiceDescriptor> descriptors = new LinkedList<OpstringServiceDescriptor>();
        for (OperationalString op : operationalStrings) {
            for (ServiceElement se : op.getServices())
                descriptors.add(new OpstringServiceDescriptor(se, policyFile));
            descriptors.addAll(createServiceDescriptors(op.getNestedOperationalStrings(), policyFile));
        }
        return descriptors;
    }

    private Map<Configuration, List<ServiceDescriptor>> instantiateDescriptors(List<String> riverServices) throws ConfigurationException {
        List<Configuration> configs = new ArrayList<Configuration>(riverServices.size());
        for (String s : riverServices) {
            configs.add(ConfigurationProvider.getInstance(new String[]{s}));
        }
        return instantiateDescriptors(configs);
    }

    public Map<Configuration, List<ServiceDescriptor>> instantiateDescriptors(Collection<Configuration> configs) throws ConfigurationException {
        Map<Configuration, List<ServiceDescriptor>> result = new HashMap<Configuration, List<ServiceDescriptor>>();
        for (Configuration config : configs) {
            ServiceDescriptor[] descs = (ServiceDescriptor[])
                    config.getEntry(START_PACKAGE, "serviceDescriptors",
                            ServiceDescriptor[].class, null);
            if (descs == null || descs.length == 0) {
                return result;
            }
            result.put(config, Arrays.asList(descs));
        }
        return result;
    }

    private static class ServiceStatHolder {
        public int started;
        public int erred;
        public int all;
    }

    /**
     * Create a service for each ServiceDescriptor in the map
     *
     * @throws Exception
     */
    public void instantiateServices(Map<Configuration, Collection<? extends ServiceDescriptor>> descriptorMap) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader classLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(platformLoader.getClassLoader());
        Binding<Set<ServiceDescriptorProcessor>> existingBinding = injector.getExistingBinding(Key.get(new TypeLiteral<Set<ServiceDescriptorProcessor>>() {
        }));
        Set<ServiceDescriptorProcessor> processors = null;
        if (existingBinding != null)
            processors = existingBinding.getProvider().get();

        ServiceStatHolder stat = new ServiceStatHolder();

        for (Collection<? extends ServiceDescriptor> descs : descriptorMap.values())
            stat.all += descs.size();

        try {
            for (Configuration config : descriptorMap.keySet()) {
                Collection<? extends ServiceDescriptor> descriptors = descriptorMap.get(config);
                ServiceDescriptor[] descs = descriptors.toArray(new ServiceDescriptor[descriptors.size()]);

                LoginContext loginContext = (LoginContext)
                        config.getEntry(START_PACKAGE, "loginContext",
                                LoginContext.class, null);
                if (loginContext != null)
                    createWithLogin(descs, config, loginContext, stat, processors);
                else
                    create(descs, config, stat, processors);
            }
        } finally {
            thread.setContextClassLoader(classLoader);
        }
    }

    /**
     * Generic service creation method that attempts to start the
     * services defined by the provided <code>ServiceDescriptor[]</code>
     * argument.
     *
     * @param descs  The <code>ServiceDescriptor[]</code> that contains
     *               the descriptors for the services to start.
     * @param config The associated <code>Configuration</code> object
     *               used to customize the service creation process.
     * @throws Exception If there was a problem creating the service.
     * @see com.sun.jini.start.ServiceStarter.Result
     * @see com.sun.jini.start.ServiceDescriptor
     * @see net.jini.config.Configuration
     */
    public void create(ServiceDescriptor[] descs, Configuration config, ServiceStatHolder stat, Set<ServiceDescriptorProcessor> processors) throws Exception {
        for (ServiceDescriptor desc : descs) {
            if (bootInterrupted)
                break;
            if (desc == null)
                continue;

            injector.injectMembers(desc);
            log.info("Creating service from {}", desc);

            if (processors != null)
                for (ServiceDescriptorProcessor processor : processors) {
                    processor.process(desc);
                }

            Service service;
            try {
                if (desc instanceof AbstractServiceDescriptor) {
                    ((AbstractServiceDescriptor) desc).addLifeCycle(this);
                    service = (Service) desc.create(config);
                } else if (desc instanceof RioServiceDescriptor) {
                    log.info("Starting RIO service");
                    RioServiceDescriptor.Created created = (RioServiceDescriptor.Created) desc.create(config);
                    service = new Service(created.impl, created.proxy, desc);
                } else {
                    log.info("Starting UNKNOWN service");
                    service = new Service(desc.create(config), null, desc);
                }
                ServiceDestroyer destroyer = getDestroyer(service.impl);
                if (destroyer == null)
                    nonDestroyServices.add(service);
                else {
                    service.destroyer = destroyer;
                    services.add(service);
                }
                ++stat.started;
            } catch (Exception e) {
                log.warn("Error while creating a service from {}", desc, e);
                ++stat.erred;
            } finally {
                log.info("Started {}/{} services; {} errors", stat.started, stat.all, stat.erred);
            }
        }
    }

    /**
     * Generic service creation method that attempts to login via
     * the provided <code>LoginContext</code> and then call the
     * <code>create</code> overload without a login context argument.
     *
     * @param descs        The <code>ServiceDescriptor[]</code> that contains
     *                     the descriptors for the services to start.
     * @param config       The associated <code>Configuration</code> object
     *                     used to customize the service creation process.
     * @param loginContext The associated <code>LoginContext</code> object
     *                     used to login/logout.
     * @throws Exception If there was a problem logging in/out or
     *                   a problem creating the service.
     * @see com.sun.jini.start.ServiceStarter.Result
     * @see com.sun.jini.start.ServiceDescriptor
     * @see net.jini.config.Configuration
     * @see javax.security.auth.login.LoginContext
     */
    private void createWithLogin(
            final ServiceDescriptor[] descs, final Configuration config,
            final LoginContext loginContext, final ServiceStatHolder stat, final Set<ServiceDescriptorProcessor> processors)
            throws Exception {
        loginContext.login();

        try {
            Subject.doAsPrivileged(
                    loginContext.getSubject(),
                    new PrivilegedExceptionAction() {
                        public Object run()
                                throws Exception {
                            create(descs, config, stat, processors);
                            return null;
                        }
                    },
                    null
            );
        } catch (PrivilegedActionException pae) {
            throw pae.getException();
        } finally {
            try {
                loginContext.logout();
            } catch (LoginException le) {
                log.warn("service.logout.exception", le);
            }
        }
    }

    private static class ArtifactIdFileFilter extends AbstractFileFilter {
        private String artifactId;

        public ArtifactIdFileFilter(String artifactId) {
            this.artifactId = artifactId;
        }

        @Override
        public boolean accept(File dir, String name) {
            String parent = dir.getName();
            String grandParent = dir.getParentFile().getName();
            return
                    new File(dir, name).isFile() && name.startsWith(artifactId + "-") && name.endsWith(".jar") && (
                            //check development structure
                            "target".equals(parent)
                                    //check repository just in case
                                    || artifactId.equals(grandParent)
                    )
                            //check distribution structure
                            || "lib".equals(grandParent) && (artifactId + ".jar").equals(name)
                    ;
        }
    }
}
