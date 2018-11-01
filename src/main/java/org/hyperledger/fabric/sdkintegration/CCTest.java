package org.hyperledger.fabric.sdkintegration;

import org.apache.commons.codec.binary.Hex;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.TransactionEventException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric.sdk.testutils.TestConfig;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hyperledger.fabric.sdk.Channel.NOfEvents.createNofEvents;
import static org.hyperledger.fabric.sdk.Channel.PeerOptions.createPeerOptions;
import static org.hyperledger.fabric.sdk.Channel.TransactionOptions.createTransactionOptions;
import static org.hyperledger.fabric.sdkintegration.EnrollTest.*;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CCTest {

    private static final String TEST_FIXTURES_PATH = "src/test/fixture";
    private static final String TESTUSER_1_NAME = "user1";

    private static final String FOO_CHANNEL_NAME = "foo";
    private static final String BAR_CHANNEL_NAME = "bar";

    private static final TestConfig testConfig = TestConfig.getConfig();
    private static final String EXPECTED_EVENT_NAME = "event";
    private static final byte[] EXPECTED_EVENT_DATA = "!".getBytes(UTF_8);

    static String CHAIN_CODE_FILEPATH = "sdkintegration/gocc/sample1";
    static String CHAIN_CODE_NAME = "example_cc_go";
    static String CHAIN_CODE_PATH = "github.com/example_cc";
    static String CHAIN_CODE_VERSION = "1";
    static TransactionRequest.Type CHAIN_CODE_LANG = TransactionRequest.Type.GO_LANG;
    private static final boolean IS_FABRIC_V10 = testConfig.isRunningAgainstFabric10();

    static String testTxID = null;

    public static void main(String[] args) throws Exception {

        if (sampleStoreFile.exists()) { //For testing start fresh
            sampleStoreFile.delete();
        }
        sampleStore = new SampleStore(sampleStoreFile);
        checkConfig();
        enrollUsersSetup(sampleStore);
        runFabricTest(sampleStore);
    }

    public static void runFabricTest(final SampleStore sampleStore) throws Exception {

        ////////////////////////////
        // Setup client

        //Create instance of client.
        HFClient client = HFClient.createNewInstance(); // 初始化客户端

        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite()); // 设置加密算法

        ////////////////////////////
        //Construct and run the channels
        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1"); //  获取peerOrg1组织1

        Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg); //  构建一个channel通道，Org1加入到该通道中

        sampleStore.saveChannel(fooChannel); // 保存通道名称到数据库中(这里是存储到上面方法文件)

        runChannel(client, fooChannel, true, sampleOrg, 0); // 安装链码、实例化链码、执行一个查询测试

        assertFalse(fooChannel.isShutdown());
        fooChannel.shutdown(true); // Force foo channel to shutdown clean up resources.
        assertTrue(fooChannel.isShutdown());

        assertNull(client.getChannel(FOO_CHANNEL_NAME));
        out("\n");

//        sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg2");
//        Channel barChannel = constructChannel(BAR_CHANNEL_NAME, client, sampleOrg);
//        assertTrue(barChannel.isInitialized());
//        /**
//         * sampleStore.saveChannel uses {@link Channel#serializeChannel()}
//         */
//        sampleStore.saveChannel(barChannel);
//        assertFalse(barChannel.isShutdown());
//        runChannel(client, barChannel, true, sampleOrg, 100); //run a newly constructed bar channel with different b value!
//        //let bar channel just shutdown so we have both scenarios.
//
//        out("\nTraverse the blocks for chain %s ", barChannel.getName());
//
        // 对区块进行各种查询，包括区块读写集、区块数量高度等
//        blockWalker(client, barChannel);
//
//        assertFalse(barChannel.isShutdown());
//        assertTrue(barChannel.isInitialized());
        out("That's all folks!");

    }


    static Channel constructChannel(String name, HFClient client, SampleOrg sampleOrg) throws Exception {
        ////////////////////////////
        //Construct the channel
        //

        out("Constructing channel %s", name);

        //boolean doPeerEventing = false;
        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && BAR_CHANNEL_NAME.equals(name);
//        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && FOO_CHANNEL_NAME.equals(name);
        //Only peer Admin org
        client.setUserContext(sampleOrg.getPeerAdmin()); // 设置操作的用户上下文 (获取peer的admin用户)

        //---------------初始化order排序节点对象------------------------
        Collection<Orderer> orderers = new LinkedList<>();

        for (String orderName : sampleOrg.getOrdererNames()) {

            Properties ordererProperties = testConfig.getOrdererProperties(orderName);

            //example of setting keepAlive to avoid timeouts on inactive http2 connections.
            // Under 5 minutes would require changes to server side to accept faster ping rates.
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveWithoutCalls", new Object[] {true});


            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName), // orderer grpc的url
                    ordererProperties));
        }
        //---------------初始化order排序节点对象-----------------------------

        //Just pick the first orderer in the list to create the channel.

        Orderer anOrderer = orderers.iterator().next();
        orderers.remove(anOrderer);

        // ----------------创建channel------------------------
        // src/test/fixture/sdkintegration/e2e-2Orgs/v1.1/foo.tx
        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(TEST_FIXTURES_PATH + "/sdkintegration/e2e-2Orgs/" + TestConfig.FAB_CONFIG_GEN_VERS + "/" + name + ".tx"));

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
        Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));

        out("Created channel %s", name);
        // ----------------创建channel------------------------

        //--------------------------创建peer 加入通道---------------------
        boolean everyother = true; //test with both cases when doing peer eventing.
        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName); // grpc://47.104.249.104:7051

            Properties peerProperties = testConfig.getPeerProperties(peerName); //test properties for peer.. if any.
            if (peerProperties == null) {
                peerProperties = new Properties();
            }


            //Example of setting specific options on grpc's NettyChannelBuilder
            peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            if (doPeerEventing && everyother) {
                newChannel.joinPeer(peer, createPeerOptions()); //Default is all roles.
            } else {
                // Set peer to not be all roles but eventing.
                newChannel.joinPeer(peer, createPeerOptions().setPeerRoles(Peer.PeerRole.NO_EVENT_SOURCE));
            }

            out("Peer %s joined channel %s", peerName, name);
            everyother = !everyother;
        }

        //--------------------------创建peer 加入通道---------------------

        //just for testing ...
        if (doPeerEventing) {
            // Make sure there is one of each type peer at the very least.
            assertFalse(newChannel.getPeers(EnumSet.of(Peer.PeerRole.EVENT_SOURCE)).isEmpty());
            assertFalse(newChannel.getPeers(Peer.PeerRole.NO_EVENT_SOURCE).isEmpty());
        }

        for (Orderer orderer : orderers) { //add remaining orderers if any.
            newChannel.addOrderer(orderer);
        }

        //----------------------给channel设置监听事件的grpc接口，进行初始化----------------------
        for (String eventHubName : sampleOrg.getEventHubNames()) {

            final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);

            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});


            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    eventHubProperties);
            newChannel.addEventHub(eventHub);
        }

        newChannel.initialize();
        //----------------------给channel设置监听事件的grpc接口，进行初始化----------------------

        out("Finished initialization channel %s", name);

        //Just checks if channel can be serialized and deserialized .. otherwise this is just a waste :)
        byte[] serializedChannelBytes = newChannel.serializeChannel();
        newChannel.shutdown(true);

        return client.deSerializeChannel(serializedChannelBytes).initialize();

    }
    static void runChannel(HFClient client, Channel channel, boolean installChaincode, SampleOrg sampleOrg, int delta) {

        class ChaincodeEventCapture { //A test class to capture chaincode events
            final String handle;
            final BlockEvent blockEvent;
            final ChaincodeEvent chaincodeEvent;

            ChaincodeEventCapture(String handle, BlockEvent blockEvent, ChaincodeEvent chaincodeEvent) {
                this.handle = handle;
                this.blockEvent = blockEvent;
                this.chaincodeEvent = chaincodeEvent;
            }
        }
        Vector<ChaincodeEventCapture> chaincodeEvents = new Vector<>(); // Test list to capture chaincode events.

        try {

            final String channelName = channel.getName();
            boolean isFooChain = FOO_CHANNEL_NAME.equals(channelName);
            out("Running channel %s", channelName);

            Collection<Orderer> orderers = channel.getOrderers();
            final ChaincodeID chaincodeID;
            Collection<ProposalResponse> responses;
            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> failed = new LinkedList<>();

            // Register a chaincode event listener that will trigger for any chaincode id and only for EXPECTED_EVENT_NAME event.

            String chaincodeEventListenerHandle = channel.registerChaincodeEventListener(Pattern.compile(".*"),
                    Pattern.compile(Pattern.quote(EXPECTED_EVENT_NAME)),
                    (handle, blockEvent, chaincodeEvent) -> {

                        chaincodeEvents.add(new ChaincodeEventCapture(handle, blockEvent, chaincodeEvent));

                        String es = blockEvent.getPeer() != null ? blockEvent.getPeer().getName() : blockEvent.getEventHub().getName();
                        out("RECEIVED Chaincode event with handle: %s, chaincode Id: %s, chaincode event name: %s, "
                                        + "transaction id: %s, event payload: \"%s\", from eventhub: %s",
                                handle, chaincodeEvent.getChaincodeId(),
                                chaincodeEvent.getEventName(),
                                chaincodeEvent.getTxId(),
                                new String(chaincodeEvent.getPayload()), es);

                    });

            //For non foo channel unregister event listener to test events are not called.
            if (!isFooChain) {
                channel.unregisterChaincodeEventListener(chaincodeEventListenerHandle);
                chaincodeEventListenerHandle = null;

            }

            ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                    .setVersion(CHAIN_CODE_VERSION);
            if (null != CHAIN_CODE_PATH) {
                chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);

            }
            chaincodeID = chaincodeIDBuilder.build();

            //--------------------------------------------安装链码 example_cc.go----------------------------------------
            if (installChaincode) {
                ////////////////////////////
                // Install Proposal Request
                //

                client.setUserContext(sampleOrg.getPeerAdmin());

                out("Creating install proposal");

                InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
                installProposalRequest.setChaincodeID(chaincodeID);

                if (isFooChain) {
                    // on foo chain install from directory.

                    ////For GO language and serving just a single user, chaincodeSource is mostly likely the users GOPATH
                    installProposalRequest.setChaincodeSourceLocation(Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile());
                } else {
                    // On bar chain install from an input stream.

                    if (CHAIN_CODE_LANG.equals(TransactionRequest.Type.GO_LANG)) {

                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH, "src", CHAIN_CODE_PATH).toFile()),
                                Paths.get("src", CHAIN_CODE_PATH).toString()));
                    } else {
                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile()),
                                "src"));
                    }
                }

                installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);
                installProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);

                out("Sending install proposal");

                ////////////////////////////
                // only a client from the same org as the peer can issue an install request
                int numInstallProposal = 0;
                //    Set<String> orgs = orgPeers.keySet();
                //   for (SampleOrg org : testSampleOrgs) {

                Collection<Peer> peers = channel.getPeers();
                numInstallProposal = numInstallProposal + peers.size();
                responses = client.sendInstallProposal(installProposalRequest, peers);

                for (ProposalResponse response : responses) {
                    if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                        out("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                        successful.add(response);
                    } else {
                        failed.add(response);
                    }
                }

                //   }
                out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size());

                if (failed.size() > 0) {
                    ProposalResponse first = failed.iterator().next();
                    fail("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
                }
            }

            //--------------------------------------------安装链码 example_cc.go----------------------------------------

            //   client.setUserContext(sampleOrg.getUser(TEST_ADMIN_NAME));
            //  final ChaincodeID chaincodeID = firstInstallProposalResponse.getChaincodeID();
            // Note installing chaincode does not require transaction no need to
            // send to Orderers

            //--------------------------------------初始化链码实例，设置背书策略------------------------------------
            ///////////////
            //// Instantiate chaincode.
            InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
            instantiateProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
            instantiateProposalRequest.setChaincodeID(chaincodeID);
            instantiateProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
            instantiateProposalRequest.setFcn("init");
            instantiateProposalRequest.setArgs(new String[] {"a", "500", "b", "" + (200 + delta)});
            Map<String, byte[]> tm = new HashMap<>();
            tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
            tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
            instantiateProposalRequest.setTransientMap(tm);

            /*
              policy OR(Org1MSP.member, Org2MSP.member) meaning 1 signature from someone in either Org1 or Org2
              See README.md Chaincode endorsement policies section for more details.
            */
            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            chaincodeEndorsementPolicy.fromYamlFile(new File(TEST_FIXTURES_PATH + "/sdkintegration/chaincodeendorsementpolicy.yaml"));
            instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

            out("Sending instantiateProposalRequest to all peers with arguments: a and b set to 100 and %s respectively", "" + (200 + delta));
            successful.clear();
            failed.clear();

            if (isFooChain) {  //Send responses both ways with specifying peers and by using those on the channel.
                responses = channel.sendInstantiationProposal(instantiateProposalRequest, channel.getPeers());
            } else {
                responses = channel.sendInstantiationProposal(instantiateProposalRequest);
            }

            //--------------------------------------初始化链码实例，设置背书策略------------------------------------

            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                    out("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                } else {
                    failed.add(response);
                }
            }
            out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());
            if (failed.size() > 0) {
                for (ProposalResponse fail : failed) {

                    out("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + fail.getMessage() + ", on peer" + fail.getPeer());

                }
                ProposalResponse first = failed.iterator().next();
                fail("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
            }

            ///////////////
            /// Send instantiate transaction to orderer
            out("Sending instantiateTransaction to orderer with a and b set to 100 and %s respectively", "" + (200 + delta));

            //Specify what events should complete the interest in this transaction. This is the default
            // for all to complete. It's possible to specify many different combinations like
            //any from a group, all from one group and just one from another or even None(NOfEvents.createNoEvents).
            // See. Channel.NOfEvents
            Channel.NOfEvents nOfEvents = createNofEvents();
            if (!channel.getPeers(EnumSet.of(Peer.PeerRole.EVENT_SOURCE)).isEmpty()) {
                nOfEvents.addPeers(channel.getPeers(EnumSet.of(Peer.PeerRole.EVENT_SOURCE)));
            }
            if (!channel.getEventHubs().isEmpty()) {
                nOfEvents.addEventHubs(channel.getEventHubs());
            }

            //----------------------------------------测试代码-----------------------------------------------
            channel.sendTransaction(successful, createTransactionOptions() //Basically the default options but shows it's usage.
                    .userContext(client.getUserContext()) //could be a different user context. this is the default.
                    .shuffleOrders(false) // don't shuffle any orderers the default is true.
                    .orderers(channel.getOrderers()) // specify the orderers we want to try this transaction. Fails once all Orderers are tried.
                    .nOfEvents(nOfEvents) // The events to signal the completion of the interest in the transaction
            ).thenApply(transactionEvent -> {

                waitOnFabric(0);

                assertTrue(transactionEvent.isValid()); // must be valid to be here.
                assertNotNull(transactionEvent.getSignature()); //musth have a signature.
                BlockEvent blockEvent = transactionEvent.getBlockEvent(); // This is the blockevent that has this transaction.
                assertNotNull(blockEvent.getBlock()); // Make sure the RAW Fabric block is returned.

                out("Finished instantiate transaction with transaction id %s", transactionEvent.getTransactionID());

                try {
                    assertEquals(blockEvent.getChannelId(), channel.getName());
                    successful.clear();
                    failed.clear();

                    client.setUserContext(sampleOrg.getUser(TESTUSER_1_NAME));

                    ///////////////
                    /// Send transaction proposal to all peers
                    TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
                    transactionProposalRequest.setChaincodeID(chaincodeID);
                    transactionProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
                    //transactionProposalRequest.setFcn("invoke");
                    transactionProposalRequest.setFcn("move");
                    transactionProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
                    transactionProposalRequest.setArgs("a", "b", "100");

                    Map<String, byte[]> tm2 = new HashMap<>();
                    tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8)); //Just some extra junk in transient map
                    tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8)); // ditto
                    tm2.put("result", ":)".getBytes(UTF_8));  // This should be returned see chaincode why.
                    tm2.put(EXPECTED_EVENT_NAME, EXPECTED_EVENT_DATA);  //This should trigger an event see chaincode why.

                    transactionProposalRequest.setTransientMap(tm2);

                    out("sending transactionProposal to all peers with arguments: move(a,b,100)");

                    Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers());
                    for (ProposalResponse response : transactionPropResp) {
                        if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                            out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                            successful.add(response);
                        } else {
                            failed.add(response);
                        }
                    }

                    // Check that all the proposals are consistent with each other. We should have only one set
                    // where all the proposals above are consistent. Note the when sending to Orderer this is done automatically.
                    //  Shown here as an example that applications can invoke and select.
                    // See org.hyperledger.fabric.sdk.proposal.consistency_validation config property.
                    Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
                    if (proposalConsistencySets.size() != 1) {
                        fail(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
                    }

                    out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
                            transactionPropResp.size(), successful.size(), failed.size());
                    if (failed.size() > 0) {
                        ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                        fail("Not enough endorsers for invoke(move a,b,100):" + failed.size() + " endorser error: " +
                                firstTransactionProposalResponse.getMessage() +
                                ". Was verified: " + firstTransactionProposalResponse.isVerified());
                    }
                    out("Successfully received transaction proposal responses.");

                    ProposalResponse resp = successful.iterator().next();
                    byte[] x = resp.getChaincodeActionResponsePayload(); // This is the data returned by the chaincode.
                    String resultAsString = null;
                    if (x != null) {
                        resultAsString = new String(x, "UTF-8");
                    }
                    assertEquals(":)", resultAsString);

                    assertEquals(200, resp.getChaincodeActionResponseStatus()); //Chaincode's status.

                    TxReadWriteSetInfo readWriteSetInfo = resp.getChaincodeActionResponseReadWriteSetInfo();
                    //See blockwalker below how to transverse this
                    assertNotNull(readWriteSetInfo);
                    assertTrue(readWriteSetInfo.getNsRwsetCount() > 0);

                    ChaincodeID cid = resp.getChaincodeID();
                    assertNotNull(cid);
                    final String path = cid.getPath();
                    if (null == CHAIN_CODE_PATH) {
                        assertTrue(path == null || "".equals(path));

                    } else {

                        assertEquals(CHAIN_CODE_PATH, path);

                    }

                    assertEquals(CHAIN_CODE_NAME, cid.getName());
                    assertEquals(CHAIN_CODE_VERSION, cid.getVersion());

                    ////////////////////////////
                    // Send Transaction Transaction to orderer
                    out("Sending chaincode transaction(move a,b,100) to orderer.");
                    return channel.sendTransaction(successful).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);

                } catch (Exception e) {
                    out("Caught an exception while invoking chaincode");
                    e.printStackTrace();
                    fail("Failed invoking chaincode with error : " + e.getMessage());
                }

                return null;

            }).thenApply(transactionEvent -> {
                try {

                    waitOnFabric(0);

                    assertTrue(transactionEvent.isValid()); // must be valid to be here.
                    out("Finished transaction with transaction id %s", transactionEvent.getTransactionID());
                    testTxID = transactionEvent.getTransactionID(); // used in the channel queries later

                    ////////////////////////////
                    // Send Query Proposal to all peers
                    //
                    String expect = "" + (300 + delta);
                    out("Now query chaincode for the value of b.");
                    QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
                    queryByChaincodeRequest.setArgs(new String[] {"b"});
                    queryByChaincodeRequest.setFcn("query");
                    queryByChaincodeRequest.setChaincodeID(chaincodeID);

                    Map<String, byte[]> tm2 = new HashMap<>();
                    tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
                    tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
                    queryByChaincodeRequest.setTransientMap(tm2);

                    Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
                    for (ProposalResponse proposalResponse : queryProposals) {
                        if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                            fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                                    ". Messages: " + proposalResponse.getMessage()
                                    + ". Was verified : " + proposalResponse.isVerified());
                        } else {
                            String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                            out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
                            assertEquals(payload, expect);
                        }
                    }

                    return null;
                } catch (Exception e) {
                    out("Caught exception while running query");
                    e.printStackTrace();
                    fail("Failed during chaincode query with error : " + e.getMessage());
                }

                return null;
            }).exceptionally(e -> {
                if (e instanceof TransactionEventException) {
                    BlockEvent.TransactionEvent te = ((TransactionEventException) e).getTransactionEvent();
                    if (te != null) {
                        throw new AssertionError(format("Transaction with txid %s failed. %s", te.getTransactionID(), e.getMessage()), e);
                    }
                }

                throw new AssertionError(format("Test failed with %s exception %s", e.getClass().getName(), e.getMessage()), e);

            }).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);

            //------------------------------------------测试代码----------------------------------


            // Channel queries

            // We can only send channel queries to peers that are in the same org as the SDK user context
            // Get the peers from the current org being used and pick one randomly to send the queries to.
            //  Set<Peer> peerSet = sampleOrg.getPeers();
            //  Peer queryPeer = peerSet.iterator().next();
            //   out("Using peer %s for channel queries", queryPeer.getName());

            BlockchainInfo channelInfo = channel.queryBlockchainInfo();
            out("Channel info for : " + channelName);
            out("Channel height: " + channelInfo.getHeight());
            String chainCurrentHash = Hex.encodeHexString(channelInfo.getCurrentBlockHash());
            String chainPreviousHash = Hex.encodeHexString(channelInfo.getPreviousBlockHash());
            out("Chain current block hash: " + chainCurrentHash);
            out("Chainl previous block hash: " + chainPreviousHash);

            // Query by block number. Should return latest block, i.e. block number 2
            BlockInfo returnedBlock = channel.queryBlockByNumber(channelInfo.getHeight() - 1);
            String previousHash = Hex.encodeHexString(returnedBlock.getPreviousHash());
            out("queryBlockByNumber returned correct block with blockNumber " + returnedBlock.getBlockNumber()
                    + " \n previous_hash " + previousHash);
            assertEquals(channelInfo.getHeight() - 1, returnedBlock.getBlockNumber());
            assertEquals(chainPreviousHash, previousHash);

            // Query by block hash. Using latest block's previous hash so should return block number 1
            byte[] hashQuery = returnedBlock.getPreviousHash();
            returnedBlock = channel.queryBlockByHash(hashQuery);
            out("queryBlockByHash returned block with blockNumber " + returnedBlock.getBlockNumber());
            assertEquals(channelInfo.getHeight() - 2, returnedBlock.getBlockNumber());

            // Query block by TxID. Since it's the last TxID, should be block 2
            returnedBlock = channel.queryBlockByTransactionID(testTxID);
            out("queryBlockByTxID returned block with blockNumber " + returnedBlock.getBlockNumber());
            assertEquals(channelInfo.getHeight() - 1, returnedBlock.getBlockNumber());

            // query transaction by ID
            TransactionInfo txInfo = channel.queryTransactionByID(testTxID);
            out("QueryTransactionByID returned TransactionInfo: txID " + txInfo.getTransactionID()
                    + "\n     validation code " + txInfo.getValidationCode().getNumber());

            if (chaincodeEventListenerHandle != null) {

                channel.unregisterChaincodeEventListener(chaincodeEventListenerHandle);
                //Should be two. One event in chaincode and two notification for each of the two event hubs

                final int numberEventsExpected = channel.getEventHubs().size() +
                        channel.getPeers(EnumSet.of(Peer.PeerRole.EVENT_SOURCE)).size();
                //just make sure we get the notifications.
                for (int i = 15; i > 0; --i) {
                    if (chaincodeEvents.size() == numberEventsExpected) {
                        break;
                    } else {
                        Thread.sleep(90); // wait for the events.
                    }

                }
                assertEquals(numberEventsExpected, chaincodeEvents.size());

                for (ChaincodeEventCapture chaincodeEventCapture : chaincodeEvents) {
                    assertEquals(chaincodeEventListenerHandle, chaincodeEventCapture.handle);
                    assertEquals(testTxID, chaincodeEventCapture.chaincodeEvent.getTxId());
                    assertEquals(EXPECTED_EVENT_NAME, chaincodeEventCapture.chaincodeEvent.getEventName());
                    assertTrue(Arrays.equals(EXPECTED_EVENT_DATA, chaincodeEventCapture.chaincodeEvent.getPayload()));
                    assertEquals(CHAIN_CODE_NAME, chaincodeEventCapture.chaincodeEvent.getChaincodeId());

                    BlockEvent blockEvent = chaincodeEventCapture.blockEvent;
                    assertEquals(channelName, blockEvent.getChannelId());
                    //   assertTrue(channel.getEventHubs().contains(blockEvent.getEventHub()));

                }

            } else {
                assertTrue(chaincodeEvents.isEmpty());
            }

            out("Running for Channel %s done", channelName);

        } catch (Exception e) {
            out("Caught an exception running channel %s", channel.getName());
            e.printStackTrace();
            fail("Test failed with error : " + e.getMessage());
        }
    }

    private static void waitOnFabric(int additional) {
        //NOOP today

    }

}
