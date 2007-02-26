package smartsockets.hub.connections;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import org.apache.log4j.Logger;

import smartsockets.direct.DirectSocket;
import smartsockets.direct.DirectSocketFactory;
import smartsockets.direct.DirectSocketAddress;
import smartsockets.hub.HubProtocol;
import smartsockets.hub.state.ClientDescription;
import smartsockets.hub.state.HubDescription;
import smartsockets.hub.state.HubList;
import smartsockets.hub.state.StateCounter;
import smartsockets.hub.state.StateSelector;

public class HubConnection extends MessageForwardingConnection {

    private static final Logger conlogger = 
        ibis.util.GetLogger.getLogger("smartsockets.hub.connections.hub"); 
     
    private static final Logger goslogger = 
        ibis.util.GetLogger.getLogger("smartsockets.hub.gossip"); 
          
    private final HubDescription peer;
    private final HubDescription local;    
    
    // Keeps the current state of the system. 
    private final StateCounter state;        
                      
    // Indicates the value of the local state the last time any data was send 
    // to the peer. Remembering this allows us to send delta's. 
    private long lastSendState;    
            
    private final String uniquePrefix;
    
    public HubConnection(DirectSocket s, DataInputStream in, 
            DataOutputStream out, HubDescription peer, 
            Map<DirectSocketAddress, BaseConnection> connections, 
            HubList proxies, StateCounter state, VirtualConnections vcs, 
            boolean master) {
        
        super(s, in, out, connections, proxies, vcs, master, "Hub(" 
                + peer.hubAddressAsString + ")");
        
        this.peer = peer;        
        this.state = state;
        
        this.uniquePrefix = peer.hubAddressAsString + "__";
        
        local = proxies.getLocalDescription();
    }
         
    protected String getUniqueID(long index) { 
        return uniquePrefix + index;
    }
        
    public synchronized void setLastSendState() {
        lastSendState = state.get();
    }
    
    public void gossip() { 
        
        long newSendState = state.get(); 

        if (goslogger.isInfoEnabled()) {             
            goslogger.info("Gossiping with: " + peer.hubAddress);
        }
        
        StateSelector ss = new StateSelector(lastSendState);
        
        knownHubs.select(ss);
        
        try {
            int writes = 0;

            for (HubDescription tmp : ss.getResult()) { 
                
                if (goslogger.isInfoEnabled()) { 
                    goslogger.info("    Writing proxy: " + tmp.hubAddressAsString);
                }
                
                if (goslogger.isDebugEnabled()) {
                    goslogger.debug("      since lastLocalUpdate="  
                            + tmp.getLastLocalUpdate()  
                            + " > lastSendState= " + lastSendState);
                }
                
                writeHub(tmp);
                writes++;
            }        
            
            if (writes == 0) {
                // No proxies where written, so write a ping instead.
                writePing();
            } 
            
            synchronized (out) { 
                out.flush();
            }
            
            lastSendState = newSendState;
            
            peer.setContactTimeStamp(false);
            
        } catch (Exception e) {
            goslogger.warn("Unhandled exception in HubConnection!!", e); 
            disconnect();
        }
                  
    }
    
    private void writePing() throws IOException {      
        synchronized (out) {
            out.write(HubProtocol.PING);
        }
    } 
    
    private void writeHub(HubDescription d) throws IOException {  
        
        synchronized (out) {
            out.write(HubProtocol.GOSSIP);

            out.writeUTF(d.hubAddress.toString());
            out.writeUTF(d.getName());
            out.writeInt(d.getHops());

            if (d.isLocal()) { 
                out.writeLong(d.getLastLocalUpdate());
            } else { 
                out.writeLong(d.getHomeState());
            } 

            ArrayList<ClientDescription> clients = d.getClients(null);
            
            out.writeInt(clients.size()); 

            for (ClientDescription c : clients) {
                c.write(out);
            }    
            
            String [] connectedTo = d.connectedTo();

            if (connectedTo == null || connectedTo.length == 0) {         
                out.writeInt(0);
                return;
            }  
          
            out.writeInt(connectedTo.length);

            for (String c : connectedTo) { 
                out.writeUTF(c);
            }
        }
    } 
        
    private void readHub() throws IOException {
                
        DirectSocketAddress address = DirectSocketAddress.getByAddress(in.readUTF());        
        String name = in.readUTF();
        
        HubDescription tmp = knownHubs.add(address);
               
        int hops = in.readInt();
        
        long state = in.readLong();
        
        int clients = in.readInt();        
        
        ClientDescription [] c = new ClientDescription[clients];
        
        for (int i=0;i<clients;i++) {
            c[i] = ClientDescription.read(in);                        
        }
        
        int conns = in.readInt();        
                
        String [] a = new String[conns];
        
        for (int i=0;i<conns;i++) {
            a[i] = in.readUTF();                        
        }
                        
        if (local.hubAddress.equals(address)) {
            // Just received information about myself!
            if (hops == 0) {
                peer.setCanReachMe();
            } else { 
                peer.setCanNotReachMe();
            }
        } else if (tmp == peer) {
            // The peer send information about itself. This should 
            // always be up-to-date.
            if (state > tmp.getHomeState()) { 
                tmp.update(c, a, name, state);
            } else if (state < tmp.getHomeState()) { 
                goslogger.warn("EEK: got information directly from " 
                        + peer.hubAddressAsString 
                        + (name.length() > 0 ? (" (" + name + ")") : "") 
                        + " which seems to be out of date! " + state 
                        + " " + tmp.getHomeState());
            }
        } else {
            // We got information about a 'third party'.               
            if (hops+1 < tmp.getHops()) {
                // We seem to have found a shorter route to the target
                if (tmp.addIndirection(peer, hops+1)) { 
                    
                    vclogger.warn("Found shortcut to hub: " 
                            + tmp.hubAddressAsString + " via "
                            + peer.hubAddressAsString + " in " + (hops+1) 
                            + " hops");
                    
                }
            } 
            
            // Check if the information is more recent than what I know...
            if (state > tmp.getHomeState()) { 
                tmp.update(c, a, name, state);
            } else if (state < tmp.getHomeState()) {
                String pn = peer.getName();
        
                if (goslogger.isDebugEnabled()) {
                    goslogger.debug("Ignoring outdated information about " 
                        + tmp.hubAddressAsString 
                        + (name.length() > 0 ? (" (" + name + ")") : "")
                        + " from " + peer.hubAddressAsString 
                        + (pn.length() > 0 ? (" (" + pn + ") ") : " ")                        
                        + state + " " 
                        + tmp.getHomeState());
                }
            }
        }
        
        peer.setContactTimeStamp(false);
    }
        
    private void handlePing() {
        if (goslogger.isInfoEnabled()) {
            goslogger.debug("Got ping from " + peer.hubAddress);
        }
        
        peer.setContactTimeStamp(false);
    }
       
    protected String getName() { 
        return "HubConnection(" + peer.hubAddress + ")";
    }
    
    private void disconnect() {
                
        // Update the administration
        connections.remove(peer.hubAddress);
        
        local.removeConnectedTo(peer.hubAddressAsString);
        peer.removeConnection();
       
        DirectSocketFactory.close(s, out, in);            
    
        closeAllVirtualConnections(uniquePrefix);
    } 
    
    protected boolean handleOpcode(int opcode) {
    
        try { 
            switch (opcode) { 
        
            case HubProtocol.GOSSIP:
                if (goslogger.isInfoEnabled()) {
                    goslogger.info("HubConnection got gossip!");
                }
                readHub();
                return true;
    
            case HubProtocol.PING:
                if (goslogger.isInfoEnabled()) {
                    goslogger.info("HubConnection got ping!");
                }
                handlePing();
                return true;
                
            default:
                conlogger.warn("HubConnection got junk!");                   
                disconnect();
                return false;
            }
                        
        } catch (Exception e) {
            conlogger.warn("HubConnection got exception!", e);
            disconnect();
        }
        
        return false;
    }

    protected void handleDisconnect(Exception e) {
        vclogger.warn("handleDisconnect not implemented in HubConnection!", e);
    }
}
