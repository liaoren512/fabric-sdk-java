package org.hyperledger.fabric.sdkintegration;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.hyperledger.fabric.protos.peer.AdminGrpc;
import org.hyperledger.fabric.protos.peer.ServerStatus;

public class AdminClient {
    private final ManagedChannel channel;
    private final AdminGrpc.AdminBlockingStub blockingStub;


    public AdminClient(String host, int port){
        channel = ManagedChannelBuilder.forAddress(host,port)
                .usePlaintext(true)
                .build();

        blockingStub = AdminGrpc.newBlockingStub(channel);
    }
    public int getStatus(){
        Empty request = Empty.newBuilder().build();
        ServerStatus response = blockingStub.getStatus(request);
        System.out.println(response.getStatusValue());
        return response.getStatusValue();
    }
    public static void main(String[] args) throws InterruptedException {
        AdminClient client = new AdminClient("47.104.249.104",8051);
        client.getStatus();

    }
}
