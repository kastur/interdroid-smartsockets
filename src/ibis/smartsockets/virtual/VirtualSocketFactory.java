package ibis.smartsockets.virtual;

import ibis.smartsockets.SmartSocketsProperties;
import ibis.smartsockets.direct.DirectSocket;
import ibis.smartsockets.direct.DirectSocketAddress;
import ibis.smartsockets.discovery.Discovery;
import ibis.smartsockets.hub.Hub;
import ibis.smartsockets.hub.servicelink.ServiceLink;
import ibis.smartsockets.util.TypedProperties;
import ibis.smartsockets.virtual.modules.AbstractDirectModule;
import ibis.smartsockets.virtual.modules.AcceptHandler;
import ibis.smartsockets.virtual.modules.ConnectModule;
import ibis.util.ThreadPool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;


/**
 * This class implements a 'virtual' socket factory.
 * 
 * @author Jason Maassen
 * @version 1.0 Jan 30, 2006
 * @since 1.0
 * 
 */
public class VirtualSocketFactory {

    private static class StatisticsPrinter implements Runnable {

        private int timeout;

        StatisticsPrinter(int timeout) {
            this.timeout = timeout;
        }

        public void run() {

            while (true) {

                int t = getTimeout();

                try {
                    Thread.sleep(t);
                } catch (Exception e) {
                    // ignore
                }

                try {
                    for (String s : VirtualSocketFactory.factories.keySet()) {
                        VirtualSocketFactory.factories.get(s)
                                .printStatistics(s);
                    }
                } catch (Exception e) {
                    // TODO: IGNORE ?
                }
            }
        }

        private synchronized int getTimeout() {
            return timeout;
        }

        public synchronized void adjustInterval(int interval) {
            if (interval < timeout) {
                timeout = interval;
            }
        }
    }

    private static final Map<String, VirtualSocketFactory> factories = new HashMap<String, VirtualSocketFactory>();

    private static VirtualSocketFactory defaultFactory = null;

    private static StatisticsPrinter printer = null;

    protected static Logger logger;

    protected static Logger conlogger;

    private static final Logger statslogger;

    static {
        ibis.util.Log.initLog4J("ibis.smartsockets");
        conlogger = Logger.getLogger("ibis.smartsockets.virtual.connect");
        logger = Logger.getLogger("ibis.smartsockets.virtual.misc");
        statslogger = Logger.getLogger("ibis.smartsockets.statistics");
    }
    
    private final ArrayList<ConnectModule> modules = new ArrayList<ConnectModule>();

    private final TypedProperties properties;

    private final int DEFAULT_BACKLOG;

    private final int DEFAULT_TIMEOUT;

    // private final int DISCOVERY_PORT;

    private final HashMap<Integer, VirtualServerSocket> serverSockets = 
        new HashMap<Integer, VirtualServerSocket>();

    private int nextPort = 3000;

    private DirectSocketAddress myAddresses;

    private DirectSocketAddress hubAddress;

    private VirtualSocketAddress localVirtualAddress;

    private String localVirtualAddressAsString;

    private ServiceLink serviceLink;

    private Hub hub;
    
    private VirtualClusters clusters;
    
    private static class HubAcceptor implements AcceptHandler {
        
        private final Hub hub;
        
        private HubAcceptor(Hub hub) { 
            this.hub = hub;
        }
        
        public void accept(DirectSocket s, int targetPort) {
            hub.delegateAccept(s);
        } 
    }

    private VirtualSocketFactory(TypedProperties p)
            throws InitializationException {

        if (logger.isInfoEnabled()) {
            logger.info("Creating VirtualSocketFactory");
        }
        
        properties = p;

        DEFAULT_BACKLOG = p.getIntProperty(SmartSocketsProperties.BACKLOG);
        DEFAULT_TIMEOUT = p.getIntProperty(SmartSocketsProperties.TIMEOUT);
        
        // NOTE: order is VERY important here!
        loadModules();

        if (modules.size() == 0) {
            logger.info("Failed to load any modules!");
            throw new InitializationException("Failed to load any modules!");
        }
        
        // Start the hub (if required)
        startHub(p);
        
        // We now create the service link. This may connect to the hub that we 
        // have just started.  
        String localCluster = p.getProperty(SmartSocketsProperties.CLUSTER_MEMBER, null);
        createServiceLink(localCluster);
        
        // Once the servicelink is up and running, we can start the modules.  
        startModules();

        if (modules.size() == 0) {
            logger.info("Failed to start any modules!");
            throw new InitializationException("Failed to load any modules!");
        }

        loadClusterDefinitions();

        localVirtualAddress = new VirtualSocketAddress(myAddresses, 0,
                hubAddress, clusters.localCluster());

        localVirtualAddressAsString = localVirtualAddress.toString();
    }

    private void startHub(TypedProperties p) throws InitializationException {
        
        if (p.booleanProperty(SmartSocketsProperties.START_HUB, false)) {

            AbstractDirectModule d = null;

            // Check if the hub should delegate it's accept call to the direct 
            // module. This way, only a single server port (and address) is 
            // needed to reach both this virtual socket factory and the hub. 
            boolean delegate = p.booleanProperty(
                    SmartSocketsProperties.HUB_DELEGATE, false);

            if (delegate) { 
                logger.info("Factory delegating hub accepts to direct module!");

                // We should now add an AcceptHandler to the direct module that
                // intercepts incoming connections for the hub. Start by finding 
                // the direct module...
                for (ConnectModule m : modules) {                     
                    if (m.module.equals("ConnectModule(Direct)")) { 
                        d = (AbstractDirectModule) m;
                        break;
                    }
                }

                if (d == null) { 
                    throw new InitializationException("Cannot start hub: " 
                            + "Failed to find direct module!");
                }

                // And add its address to the property set as the 'delegation' 
                // address. This is needed by the hub (since it needs to know 
                // its own address).
                p.setProperty(SmartSocketsProperties.HUB_DELEGATE_ADDRESS, 
                        d.getAddresses().toString());
            }

            // Now we create the hub
            logger.info("Factory is starting hub");

            try {            
                hub = new Hub(p);
                logger.info("Hub running on: " + hub.getHubAddress());
            } catch (IOException e) {
                throw new InitializationException("Failed to start hub", e);
            }   

            // Finally, if delegation is used, we install the accept handler             
            if (delegate) {
                
                // Get the 'virtual port' that the hub pretends to be on.
                int port = p.getIntProperty(
                        SmartSocketsProperties.HUB_VIRTUAL_PORT, 42);

                d.installAcceptHandler(port, new HubAcceptor(hub));
            }            
        }
    }
    
    private void loadClusterDefinitions() {
        clusters = new VirtualClusters(this, properties, getModules());
    }

    private DirectSocketAddress discoverHub(String localCluster) {
        
        DirectSocketAddress address = null;

        if (logger.isInfoEnabled()) {
            logger.info("Attempting to discover hub using UDP multicast...");
        }

        int port = properties.getIntProperty(SmartSocketsProperties.DISCOVERY_PORT);
        int time = properties.getIntProperty(SmartSocketsProperties.DISCOVERY_TIMEOUT);

        Discovery d = new Discovery(port, 0, time);

        String message = "Any Proxies? ";

        message += localCluster;

        String result = d.broadcastWithReply(message);

        if (result != null) {
            try {
                address = DirectSocketAddress.getByAddress(result);
                if (logger.isInfoEnabled()) {
                    logger.info("Hub found at: " + address.toString());
                }
            } catch (Exception e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Got unknown reply to hub discovery!");
                }
            }
        } else {
            if (logger.isInfoEnabled()) {
                logger.info("No hubs found.");
            }
        }

        return address;
    }
    
    private void createServiceLink(String localCluster) {

        List<DirectSocketAddress> hubs = new LinkedList<DirectSocketAddress>();
        
        // Check if the hub address was passed as a property.
        String [] tmp = properties.getStringList(
                SmartSocketsProperties.HUB_ADDRESSES);

        if (tmp != null && tmp.length == 0) {
            for (String a : tmp) {             
                try {
                    hubs.add(DirectSocketAddress.getByAddress(a));
                } catch (Exception e) {
                    logger.warn("Failed to understand hub address: " + tmp, e);
                }
            }
        }

        // If we don't have a hub address, we try to find one ourselves
        if (hubs.size() == 0) {            
            boolean useDiscovery = properties.booleanProperty(
                    SmartSocketsProperties.DISCOVERY_ALLOWED, false);

            boolean discoveryPreferred = properties.booleanProperty(
                    SmartSocketsProperties.DISCOVERY_PREFERRED, false);
            
            DirectSocketAddress address = null;
            
            if (useDiscovery && (discoveryPreferred || hub == null)) { 
                address = discoverHub(localCluster);                    
            }            
            
            if (address == null && hub != null) { 
                address = hub.getHubAddress();
            } 
            
            if (address != null) { 
                hubs.add(address);
            }
        } 

        // Still no address ? Give up...
        if (hubs.size() == 0) {
            // properties not set, so no central hub is available
            // if (logger.isInfoEnabled()) {
            System.out.println("ServiceLink not created: no hub address available!");
            logger.info("ServiceLink not created: no hub address available!");
            // }
            return;
        }

        try {
            serviceLink = ServiceLink.getServiceLink(properties, hubs,
                    myAddresses);

            hubAddress = serviceLink.getAddress();

            if (true) {
                serviceLink.waitConnected(10000);
            }
        } catch (Exception e) {
            logger.warn("Failed to connect service link to hub!", e);
            return;
        }

        // Check if the users want us to register any properties with the hub.
        String[] props = properties.getStringList(
                "smartsockets.register.property", ",", null);

        if (props != null && props.length > 0) {
            try {
                if (props.length == 1) {
                    serviceLink.registerProperty(props[0], "");
                } else {
                    serviceLink.registerProperty(props[0], props[1]);
                }
            } catch (Exception e) {

                if (props.length == 1) {
                    logger
                            .warn("Failed to register user property: "
                                    + props[0]);
                } else {
                    logger.warn("Failed to register user property: " + props[0]
                            + "=" + props[1]);
                }
            }
        }
    }

    private ConnectModule loadModule(String name) {

        if (logger.isInfoEnabled()) {
            logger.info("Loading module: " + name);
        }

        String classname = properties.getProperty(SmartSocketsProperties.MODULES_PREFIX
                + name, null);

        if (classname == null) {
            // The class implementing the module is not explicitly defined, so
            // instead we use an 'educated guess' of the form:
            //
            // smartsockets.virtual.modules.<name>.<Name>
            //            
            StringBuffer tmp = new StringBuffer();
            tmp.append("ibis.smartsockets.virtual.modules.");
            tmp.append(name.toLowerCase());
            tmp.append(".");
            tmp.append(Character.toUpperCase(name.charAt(0)));
            tmp.append(name.substring(1));
            classname = tmp.toString();
        }

        if (logger.isInfoEnabled()) {
            logger.info("    class name: " + classname);
        }

        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class c = cl.loadClass(classname);

            // Check if the class we loaded is indeed a flavor of ConnectModule
            if (!ConnectModule.class.isAssignableFrom(c)) {
                logger.warn("Cannot load module " + classname + " since it is "
                        + " not a subclass of ConnectModule!");
                return null;
            }

            return (ConnectModule) c.newInstance();

        } catch (Exception e) {
            logger.info("Failed to load module " + classname, e);
        }

        return null;
    }

    private void loadModules() {
        // Get the list of modules that we should load. 
        String [] mods = properties.getStringList(SmartSocketsProperties.MODULES_DEFINE,
                ",", new String [0]);

        if (mods == null || mods.length == 0) {
            // Should not happen!
            logger.error("No smartsockets modules defined!");
            return;
        }

        // Get the list of modules to skip. Note that the direct module cannot 
        // be skipped. 
        String [] skip = properties.getStringList(SmartSocketsProperties.MODULES_SKIP, ",",
                null);

        int count = mods.length;
        
        // Remove all modules that should be skipped.
        if (skip != null) {
            for (int s = 0; s < skip.length; s++) {
                for (int m = 0; m < mods.length; m++) {
                    if (skip[s].equals(mods[m])) {
                        if (logger.isInfoEnabled()) {
                            logger.info("Skipping module " + mods[m]);
                        }
                        mods[m] = null;
                        count--;
                    }
                }
            }
        }

        String t = "";

        for (int i = 0; i < mods.length; i++) {
            if (mods[i] != null) {
                t += mods[i] + " ";
            }
        }
        
        if (logger.isInfoEnabled()) {
            logger.info("Loading " + count + " modules: " + t);
        }

        if (count == 0) {
            logger.error("No smartsockets modules left after filtering!");
            return;
        }
        
        for (int i = 0; i < mods.length; i++) {

            if (mods[i] != null) {
                try {
                    ConnectModule m = loadModule(mods[i]);
                    m.init(this, mods[i], properties, logger);

                    DirectSocketAddress tmp = m.getAddresses();

                    if (tmp != null) {
                        if (myAddresses == null) {
                            myAddresses = tmp;
                        } else {
                            myAddresses = DirectSocketAddress.merge(
                                    myAddresses, tmp);
                        }
                    }

                    modules.add(m);
                } catch (Exception e) {
        
                    if (logger.isInfoEnabled()) {
                        logger.info("Failed to load module: " + mods[i], e);
                    }
                    
                    mods[i] = null;
                    count--;
                }
            }
        }
        
        if (myAddresses == null) {
            logger.info("Failed to retrieve my own address!");        
            modules.clear();      
            return;
        }
            
        if (logger.isInfoEnabled()) {
            logger.info(count + " modules loaded.");
        }
    }

    protected ConnectModule[] getModules() {
        return modules.toArray(new ConnectModule[modules.size()]);
    }

    protected ConnectModule[] getModules(String[] names) {

        ArrayList<ConnectModule> tmp = new ArrayList<ConnectModule>();

        for (int i = 0; i < names.length; i++) {

            boolean found = false;

            if (names[i] != null && !names[i].equals("none")) {
                for (int j = 0; j < modules.size(); j++) {
                    ConnectModule m = modules.get(j);

                    if (m.getName().equals(names[i])) {
                        tmp.add(m);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    logger.warn("Module " + names[i] + " not found!");
                }
            }
        }

        return tmp.toArray(new ConnectModule[tmp.size()]);
    }

    private void startModules() {

        ArrayList<ConnectModule> failed = new ArrayList<ConnectModule>();

        if (serviceLink == null) {
            // No servicelink, so remove all modules that depend on it....
            for (ConnectModule c : modules) {
                if (c.requiresServiceLink) {
                    failed.add(c);
                }
            }

            for (ConnectModule c : failed) {
                logger.info("Module " + c.module
                        + " removed (no serviceLink)!");
                modules.remove(c);
            }

            failed.clear();
        }

        for (ConnectModule c : modules) {
            try {
                c.startModule(serviceLink);
            } catch (Exception e) {
                // Remove all modules that fail to start...
                logger.warn("Module " + c.module
                        + " did not accept serviceLink!", e);
                failed.add(c);
            }
        }

        for (ConnectModule c : failed) {
            logger.warn("Module " + c.module
                    + " removed (exception during setup)!");
            modules.remove(c);
        }

        failed.clear();
    }

    public VirtualServerSocket getServerSocket(int port) {
        synchronized (serverSockets) {
            return serverSockets.get(port);
        }
    }

    public ConnectModule findModule(String name) {

        for (ConnectModule m : modules) {
            if (m.module.equals(name)) {
                return m;
            }
        }

        return null;
    }

    public static void close(VirtualSocket s, OutputStream out, InputStream in) {

        try {
            if (out != null) {
                out.close();
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            if (in != null) {
                in.close();
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            if (s != null) {
                s.close();
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private VirtualSocket createClientSocket(ConnectModule m,
            VirtualSocketAddress target, int timeout, int timeLeft, 
            Map<String, Object> properties) throws IOException {

        int backoff = 500;
        
        if (!m.matchRuntimeRequirements(properties)) {
            if (conlogger.isInfoEnabled()) {
                conlogger.warn("Failed: module " + m.module
                        + " may not be used to set " + "up connection to "
                        + target);
            }

            m.notAllowed();
            return null;
        }
            
        if (conlogger.isDebugEnabled()) {
            conlogger.debug("Using module " + m.module + " to set up "
                    + "connection to " + target + " timeout = " + timeout 
                    + " timeleft = " + timeLeft);
        }

        // We now try to set up a connection. Normally we may not exceed the 
        // timeout, but when we succesfully create a connection we are allowed
        // to extend this time to timeLeft (either to wait for an accept, or to
        // retry after a TargetOverLoaded exception). Note that any exception 
        // other than ModuleNotSuitable or TargetOverloaded is passed to the 
        // user.
        VirtualSocket vs = null;
        int overloaded = 0;
        
        long start = System.currentTimeMillis();
        
        while (true) {         
            
            long t = System.currentTimeMillis() - start;
       
            // Check if we ran out of time. If so, the throw a target overloaded 
            // exception or a timeout exception depending on the value of the 
            // overloaded counter.  
            if (t >= timeLeft) { 
        
                if (conlogger.isDebugEnabled()) {
                    conlogger.debug("Timeout while using module " + m.module 
                            + " to set up "
                            + "connection to " + target + " timeout = " + timeout 
                            + " timeleft = " + timeLeft + " t = " + t);
                }
                    
                if (overloaded > 0) { 
                    throw new TargetOverloadedException("Failed to create "
                            + "virtual connection to " + target + " within "
                            + timeLeft + " ms. (Target overloaded " 
                            + overloaded + " times)");
                } else { 
                    throw new SocketTimeoutException("Timeout while waiting for"
                            + " accept on virtual connection to " + target);
                }
            }
            
            try {
                vs = m.connect(target, timeout, properties);
            } catch (ModuleNotSuitableException e) {
                long end = System.currentTimeMillis();

                // Just print and try the next module...
                if (conlogger.isInfoEnabled()) {
                    conlogger.info(getVirtualAddressAsString() + ": Failed "
                            + m.module + " not suitable (time = "
                            + (end - start) + " ms.)");
                }

                m.failed(end - start);
                return null;
                
                // NOTE: all IOExceptions are forwarded to the user! 
            } 

            t = System.currentTimeMillis() - start;
            
            if (vs != null) { 
                // We now have a connection to the correct machine and must wait 
                // for an accept from the serversocket. Since we don't have to 
                // try any other modules, we are allowed to spend all of the 
                // time that is left. Therefore, we start by calculating a new 
                // timeout here, which is based on the left over time for the 
                // entire connect call, minus the time we have spend so far in 
                // this connect. This is the timeout we pass to 'waitForAccept'. 
                int newTimeout = (int) (timeLeft - t);
                
                if (newTimeout <= 0) {
                    // Bit of a hack. If we run out of time at the last moment
                    // we allow some extra time to finish the connection setup.
                    // TODO: should we do this ?
                    newTimeout = 1000;
                }
                
                if (conlogger.isInfoEnabled()) {
                    conlogger.info(getVirtualAddressAsString()
                            + ": Success " + m.module + " connected to "
                            + target + " now waiting for accept (for max. " 
                            + newTimeout + " ms.)");
                }
                                
                try { 
                    vs.waitForAccept(newTimeout);
                    vs.setTcpNoDelay(false);
                        
                    long end = System.currentTimeMillis();
                    
                    if (conlogger.isInfoEnabled()) {
                        conlogger.info(getVirtualAddressAsString()
                                + ": Success " + m.module + " connected to "
                                + target + " (time = " + (end - start)
                                + " ms.)");
                    }

                    m.success(end - start);
                    return vs;
                } catch (TargetOverloadedException e) { 
                    if (conlogger.isDebugEnabled()) {
                        conlogger.debug("Connection failed, target " + target 
                                + " overloaded (" + overloaded + ") while using " 
                                + " module " + m.module);
                    }

                    overloaded++;
                   
                    t = System.currentTimeMillis() - start;
                    
                    int leftover = (int) (timeLeft-t);
                    
                    if (leftover < 0) { 
                        leftover = 0;
                    } 
                        
                    int sleeptime = Math.min(backoff, leftover);
                    
                    if (leftover > 500 && sleeptime == leftover) {
                        // In the last attempt we sleep half a second shorter. 
                        // This allows us to attempt a connection setup.
                        sleeptime -= 500;
                    }
                
                    try { 
                        Thread.sleep(sleeptime);
                    } catch (Exception x) {
                        // ignored
                    }
                    
                    backoff *= 2;
                
                } catch (IOException e) {

                    if (conlogger.isDebugEnabled()) {
                        conlogger.debug("Connection failed due to exception", e);
                    }
    
                    throw e;
                }
            } 
        }
    }

    public VirtualSocket createClientSocket(VirtualSocketAddress target,
            int timeout, Map<String, Object> prop) throws IOException {

        // Note: it's up to the user to ensure that this thing is large enough!
        // i.e., it should be of size 1+modules.length
        
        if (conlogger.isDebugEnabled()) { 
            conlogger.debug("createClientSocket(" + target + ", " + timeout 
                    + ", " + prop + ")");
        }
        
        long[] timing = null;

        if (prop != null) {
            timing = (long[]) prop.get("virtual.detailed.timing");

            if (timing != null) {
                timing[0] = System.nanoTime();
            }
        }

        try {

            int notSuitableCount = 0;

            if (timeout <= 0) {
                timeout = DEFAULT_TIMEOUT;
            }

            ConnectModule[] order = clusters.getOrder(target);

            int timeLeft = timeout;
            int partialTimeout;

            if (order.length > 1) {
                partialTimeout = (timeout / order.length);
            } else { 
                partialTimeout = timeout;
            }

            // Now try the remaining modules (or all of them if we weren't
            // using the cache in the first place...)
            for (int i = 0; i < order.length; i++) {

                ConnectModule m = order[i];

                long start = System.currentTimeMillis();

                if (timing != null) {
                    timing[1 + i] = System.nanoTime();

                    if (i > 0) {
                        prop.put("direct.detailed.timing.ignore", null);
                    }
                }

                VirtualSocket vs = createClientSocket(m, target,
                        partialTimeout, timeLeft, prop);

                if (timing != null) {
                    timing[1 + i] = System.nanoTime() - timing[1 + i];
                }

                if (vs != null) {
                    if (notSuitableCount > 0) {
                        // We managed to connect, but not with the first module,
                        // so
                        // we remember this to speed up later connections.
                        clusters.succes(target, m);
                    }
                    return vs;
                }

                if (order.length > 1 && i < order.length - 1) {
                    timeLeft -= System.currentTimeMillis() - start;

                    if (timeLeft <= 0) {
                        // TODO can this happen ?
                        throw new SocketTimeoutException("Timeout during " +
                                "connect to " + target);
                    } else {
                        partialTimeout = (timeLeft / (order.length - (i + 1)));
                    }
                }

                notSuitableCount++;
            }

            if (notSuitableCount == order.length) {
                if (logger.isInfoEnabled()) {
                    logger.info("No suitable module found to connect to "
                            + target);
                }

                // No suitable modules found...
                throw new ConnectException(
                        "No suitable module found to connect to " + target);
            } else {
                // Apparently, some modules where suitable but failed to
                // connect.
                // This is treated as a timeout
                if (logger.isInfoEnabled()) {
                    logger.info("None of the modules could to connect to "
                            + target);
                }

                // TODO: is this right ?
                throw new SocketTimeoutException("Timeout during connect to "
                        + target);
            }

        } finally {
            if (timing != null) {
                timing[0] = System.nanoTime() - timing[0];
                prop.remove("direct.detailed.timing.ignore");
            }
        }
    }

    private int getPort() {

        // TODO: should this be random ?
        synchronized (serverSockets) {
            while (true) {
                if (!serverSockets.containsKey(nextPort)) {
                    return nextPort++;
                } else {
                    nextPort++;
                }
            }
        }
    }

    public VirtualServerSocket createServerSocket(int port, int backlog,
            boolean retry, Map properties) {

        VirtualServerSocket result = null;

        while (result == null) {
            try {
                result = createServerSocket(port, backlog, null);
            } catch (Exception e) {
                // retry
                if (logger.isDebugEnabled()) {
                    logger.debug("Failed to open serversocket on port " + port
                            + " (will retry): ", e);
                }
            }
        }

        return result;
    }

    public VirtualServerSocket createServerSocket(
            Map<String, Object> properties) throws IOException {
        return new VirtualServerSocket(this, properties);
    }

    protected void bindServerSocket(VirtualServerSocket vss, int port) 
        throws BindException {
        
        synchronized (serverSockets) {
            if (serverSockets.containsKey(port)) {
                throw new BindException("Port " + port + " already in use!");
            }

            serverSockets.put(port, vss);
        }
    }
    
    public VirtualServerSocket createServerSocket(int port, int backlog,
            Map<String, Object> properties) throws IOException {

        if (backlog <= 0) {
            backlog = DEFAULT_BACKLOG;
        }

        if (port <= 0) {
            port = getPort();
        }

        if (logger.isInfoEnabled()) {
            logger.info("Creating VirtualServerSocket(" + port + ", " + backlog
                    + ", " + properties + ")");
        }

        synchronized (serverSockets) {
            if (serverSockets.containsKey(port)) {
                throw new BindException("Port " + port + " already in use!");
            }

            VirtualSocketAddress a = new VirtualSocketAddress(myAddresses,
                    port, hubAddress, clusters.localCluster());

            VirtualServerSocket vss = new VirtualServerSocket(this, a, port,
                    backlog, properties);

            serverSockets.put(port, vss);
            return vss;
        }
    }

    // TODO: hide this thing ?
    public ServiceLink getServiceLink() {
        return serviceLink;
    }

    public DirectSocketAddress getLocalHost() {
        return myAddresses;
    }

    public String getLocalCluster() {
        return clusters.localCluster();
    }

    public DirectSocketAddress getLocalHub() {
        return hubAddress;
    }

    public void addHubs(DirectSocketAddress [] hubs) {
        if (hub != null) { 
            hub.addHubs(hubs);
        } else if (serviceLink != null) { 
            serviceLink.addHubs(hubs);
        }
    }
            
    public DirectSocketAddress [] getKnownHubs() {
        
        if (hub != null) { 
            return hub.knownHubs();
        } else if (serviceLink != null) {            
            try {
                return serviceLink.hubs();
            } catch (IOException e) {
                logger.info("Failed to retrieve hub list!", e);
            }
        }
        
        return null;        
    }
    
    public void end() { 
        if (hub != null) { 
            hub.end();
        }
    }
    
    protected void closed(int port) {
        synchronized (serverSockets) {
            serverSockets.remove(new Integer(port));
        }
    }

    public static synchronized VirtualSocketFactory getSocketFactory(String name) {
        return factories.get(name);
    }

    public static synchronized VirtualSocketFactory getOrCreateSocketFactory(
            String name, java.util.Properties p, boolean addDefaults)
            throws InitializationException {
        VirtualSocketFactory result = factories.get(name);

        if (result == null) {
            result = createSocketFactory(p, addDefaults);
            factories.put(name, result);
        } else if (!p.equals(result.properties)) {
            throw new InitializationException(
                    "could not retrieve existing factory, properties are not equal");

        }

        return result;
    }

    public static synchronized void registerSocketFactory(String name,
            VirtualSocketFactory factory) {
        factories.put(name, factory);
    }

    public static synchronized VirtualSocketFactory getDefaultSocketFactory()
            throws InitializationException {

        if (defaultFactory == null) {
            defaultFactory = createSocketFactory();
        }

        return defaultFactory;
    }

    public static VirtualSocketFactory createSocketFactory()
            throws InitializationException {

        return createSocketFactory(null, true);
    }

    public static VirtualSocketFactory createSocketFactory(Map p,
            boolean addDefaults) throws InitializationException {
        return createSocketFactory(new TypedProperties(p), addDefaults);
    }

    public static VirtualSocketFactory createSocketFactory(
            java.util.Properties properties, boolean addDefaults)
            throws InitializationException {

        TypedProperties typedProperties = new TypedProperties();

        if (addDefaults) {
            typedProperties.putAll(SmartSocketsProperties.getDefaultProperties());
        }

        if (properties != null) {
            typedProperties.putAll(properties);
        }

        VirtualSocketFactory factory = new VirtualSocketFactory(typedProperties);

        if (typedProperties.containsKey("smartsockets.factory.statistics")) {

            int tmp = typedProperties.getIntProperty(SmartSocketsProperties.STATISTICS_INTERVAL,
                    0);

            if (tmp > 0) {

                if (tmp < 1000) {
                    tmp *= 1000;
                }

                if (printer == null) {
                    printer = new StatisticsPrinter(tmp);
                    ThreadPool.createNew(printer,
                            "SmartSockets Statistics Printer");
                } else {
                    printer.adjustInterval(tmp);
                }
            }
        }

        return factory;
    }

    public VirtualSocket createBrokeredSocket(InputStream brokered_in,
            OutputStream brokered_out, boolean b, Map p) {
        throw new RuntimeException("createBrokeredSocket not implemented");
    }

    public VirtualSocketAddress getLocalVirtual() {
        return localVirtualAddress;
    }

    public String getVirtualAddressAsString() {
        return localVirtualAddressAsString;
    }

    public void printStatistics(String prefix) {

        if (statslogger.isInfoEnabled()) {
            statslogger.info(prefix + " === VirtualSocketFactory ("
                    + modules.size() + " / "
                    + (serviceLink == null ? "No SL" : "SL") + ") ===");

            for (ConnectModule c : modules) {
                c.printStatistics(prefix);
            }

            if (serviceLink != null) {
                serviceLink.printStatistics(prefix);
            }
        }

    }
}
