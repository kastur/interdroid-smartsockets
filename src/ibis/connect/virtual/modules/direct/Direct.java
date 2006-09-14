package ibis.connect.virtual.modules.direct;

import ibis.connect.direct.DirectServerSocket;
import ibis.connect.direct.DirectSocket;
import ibis.connect.direct.DirectSocketFactory;
import ibis.connect.direct.SocketAddressSet;
import ibis.connect.virtual.ModuleNotSuitableException;
import ibis.connect.virtual.VirtualSocket;
import ibis.connect.virtual.VirtualSocketAddress;
import ibis.connect.virtual.modules.AbstractDirectModule;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Direct extends AbstractDirectModule {
    
    protected static final byte ACCEPT              = 1;
    protected static final byte PORT_NOT_FOUND      = 2;
    protected static final byte WRONG_MACHINE       = 3;     
    protected static final byte CONNECTION_REJECTED = 4;   
           
    private DirectSocketFactory direct;   
    private AcceptThread acceptThread;
    private DirectServerSocket server; 
    
    private class AcceptThread extends Thread { 
        
        AcceptThread() { 
            super("DirectModule AcceptThread");
            setDaemon(true);
        }
                     
        public void run() {         
            while (true) {
                handleAccept();                
            }
        }
    }
        
    public Direct() {
        super("ConnectModule(Direct)", false);
    } 
    
    public void initModule(Map properties) throws Exception {     

        // Retrieve the value of the port property (if set). Default value 
        // is '0' (any available port).
        int port = 0;
        
        if (properties != null) { 
        
            String tmp = (String) properties.get("modules.direct.port");
        
            if (tmp != null) {
                port = Integer.parseInt(tmp);
            }
        }
        
        System.err.println("***** PORT SET TO " + port);
        
        // Create a direct socket factory.
        direct = DirectSocketFactory.getSocketFactory();
                
        // Create a server socket to accept incoming connections. 
        HashMap prop = new HashMap();
        prop.put("PortForwarding", "yes");
        prop.put("ForwardingMayFail", "yes");
        prop.put("SameExternalPort", "no");
                
        try {            
            if (logger.isDebugEnabled()) { 
                logger.debug(name + ": Creating ServerSocket on port " + port);
            }
                        
            server = direct.createServerSocket(port, 100, prop);

            logger.info(name + ": ServerSocket created: "
                    + server.getAddressSet());
            
        } catch (IOException e) {            
            logger.warn(name + ": Failed to create ServerSocket on port " 
                    + port, e);
            throw e;
        }
        
        logger.info(name + ": Starting AcceptThread");

        // Finally start a thread to handle the incoming connections.
        acceptThread = new AcceptThread();
        acceptThread.start();
    }
    
    public void startModule() throws Exception {
        // nothing to do here...
    }
            
    public SocketAddressSet getAddresses() { 
        return server.getAddressSet();
    }
    
    /*
    private boolean checkTarget(SocketAddressSet target) {  
        // TODO: implement
        return true;
    }
    
    private void handleSocket(DirectSocket s) {
        
        DataInputStream in = null;
        DataOutputStream out = null;
        
        if (logger.isDebugEnabled()) { 
            logger.debug(name + ": Got incoming connection on " + s);
        }
           
        try {         
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
        
            SocketAddressSet target = new SocketAddressSet(in.readUTF());            
            int targetPort = in.readInt();
    
            if (logger.isDebugEnabled()) { 
                logger.debug(name + ": Target port " + targetPort);
            }
                    
            // First check if we are the desired target machine...
            if (!checkTarget(target)) { 
                out.write(WRONG_MACHINE);
                out.flush();                
                DirectSocketFactory.close(s, out, in);
                
                if (logger.isDebugEnabled()) { 
                    logger.debug(name + ": Connection failed, WRONG machine!");
                }
                
                return;
            }
            
            // Next check if the port exists locally
            VirtualServerSocket vss = parent.getServerSocket(targetPort);
            
            if (vss == null) { 
                out.write(PORT_NOT_FOUND);
                out.flush();                
                DirectSocketFactory.close(s, out, in);
                
                if (logger.isDebugEnabled()) { 
                    logger.debug(name + ": Connection failed, PORT not found!");
                }                
                
                return;
            }
            
            if (logger.isDebugEnabled()) { 
                logger.debug(name + ": Connection seems OK, checking is " +
                        "server is willing to accept");
            }
            
            // Next check if the serverSocket is willing to accept
            DirectVirtualSocket dvs = new DirectVirtualSocket(
                    new VirtualSocketAddress(target, targetPort), s, out, in, null); 
                        
            boolean accept = vss.incomingConnection(dvs);
            
            if (!accept) {
                out.write(CONNECTION_REJECTED);
                out.flush();                
                DirectSocketFactory.close(s, out, in);
                
                if (logger.isDebugEnabled()) { 
                    logger.debug(name + ": Connection failed, REJECTED!");
                }
                
                return;
            }
            
        } catch (Exception e) {            
            logger.warn(name + ": Got exception during connection setup!", e);            
            DirectSocketFactory.close(s, out, in);
        }
    }
    */
    
    void handleAccept() {    
        try { 
            handleAccept(server.accept());    
        } catch (IOException e) {
            logger.warn(name + ": Got exception while waiting " +
                    "for connection!", e);
        }                
    }
                
    public VirtualSocket connect(VirtualSocketAddress target, int timeout,
            Map properties) throws ModuleNotSuitableException, IOException {

        DirectSocket s = null;

        try { 
            s = direct.createSocket(target.machine(), timeout, properties);                    
        } catch (IOException e) {
            // Failed to create the connection, but other modules may be more 
            // succesful.            
            throw new ModuleNotSuitableException(name + ": Failed to " +
                    "connect to " + target + " " + e);           
        }
        
        return handleConnect(target, s, timeout, properties);
        
        /*
        DirectVirtualSocket tmp = null;
        
        try { 
            s.setSoTimeout(timeout);
            
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
                        
            tmp = new DirectVirtualSocket(target, s, out, in, properties);
                                                     
            out.writeUTF(target.machine().toString());
            out.writeInt(target.port());
            out.flush();                                
        } catch (IOException e) {
            // This module worked fine, but we got a 'normal' exception while 
            // connecting (i.e., because the other side refused to connection). 
            // There is no use trying other modules.            
            DirectSocketFactory.close(s, out, in);
            throw e;
        }
                
        // Now wait until the other side agrees to the connection (may throw an
        // exceptionand close the socket if something is wrong) 
        tmp.waitForAccept();
        
        // Reset the timeout to the default value (infinite). 
        s.setSoTimeout(0);
        
        return tmp;
        */
    }

    public boolean matchAdditionalRuntimeRequirements(Map requirements) {
        // No additional properties, so always matches requirements.
        return true;
    }

    protected VirtualSocket createVirtualSocket(VirtualSocketAddress a, 
            DirectSocket s, DataOutputStream out, DataInputStream in) {        
        return new DirectVirtualSocket(a, s, out, in, null); 
    }   
}
