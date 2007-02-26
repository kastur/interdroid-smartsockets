package smartsockets.hub.state;

import java.util.LinkedList;
import smartsockets.direct.DirectSocketAddress;

public class HubsForClientSelector extends Selector {
    
    private LinkedList<HubDescription> result = new LinkedList<HubDescription>();    
    private final DirectSocketAddress client;
    private final boolean includeLocal;
    
    public HubsForClientSelector(DirectSocketAddress client, boolean includeLocal) { 
        this.client = client;
        this.includeLocal = includeLocal;
    }
    
    public boolean needAll() {
        return true;
    }
    
    public void select(HubDescription description) {

        if (description.containsClient(client)) {

            // Alway add remote hubs, but only add the local one if specified!
            if (!description.isLocal() || includeLocal) { 
                result.add(description);
            } 
        } 
    }
    
    public LinkedList<HubDescription> getResult() {
        return result;
    }          
}
