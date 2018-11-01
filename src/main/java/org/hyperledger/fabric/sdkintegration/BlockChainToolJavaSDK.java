package org.hyperledger.fabric.sdkintegration;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.openssl.PEMWriter;
import org.hyperledger.fabric.protos.peer.Query;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.Peer.PeerRole;
import org.hyperledger.fabric.sdk.exception.*;
import org.hyperledger.fabric.sdk.helper.Config;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric.sdk.testutils.TestConfig;
import org.hyperledger.fabric.sdkintegration.SampleOrg;
import org.hyperledger.fabric.sdkintegration.SampleStore;
import org.hyperledger.fabric.sdkintegration.SampleUser;
import org.hyperledger.fabric_ca.sdk.EnrollmentRequest;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.hyperledger.fabric.sdkintegration.Util;
import org.hyperledger.fabric.sdk.TransactionRequest.Type;

import static org.hyperledger.fabric.sdk.Channel.TransactionOptions.createTransactionOptions;
import static org.hyperledger.fabric.sdk.testutils.TestUtils.resetConfig;
import static org.hyperledger.fabric.sdk.Channel.PeerOptions.createPeerOptions;
import static org.hyperledger.fabric.sdk.Channel.NOfEvents.createNofEvents;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BlockChainToolJavaSDK {

    public static final int INT = 288;
    public static final int INT1 = 299;
    public static final int INT2 = 300;
    private static BlockChainToolJavaSDK instance = new BlockChainToolJavaSDK();
	
	private static final TestConfig testConfig = TestConfig.getConfig();
	private static final String TEST_ADMIN_NAME = "admin";
    private static final String TESTUSER_1_NAME = "user1";
    private static final String TEST_FIXTURES_PATH = "D:/uepstudio/workspace_idea/BlockTest/src/test/fixture";
    
    private static final String FOO_CHANNEL_NAME = "foo";
    private static final String BAR_CHANNEL_NAME = "bar";
	
    private static final String EXPECTED_EVENT_NAME = "event";
    private static final byte[] EXPECTED_EVENT_DATA = "!".getBytes(UTF_8);

    private HFClient client = HFClient.createNewInstance();

    String CHAIN_CODE_FILEPATH = "sdkintegration/gocc/sample1";
    String CHAIN_CODE_NAME = "example_cc_go";
    String CHAIN_CODE_PATH = "github.com/example_cc";
    String CHAIN_CODE_VERSION = "1";
    Type CHAIN_CODE_LANG = Type.GO_LANG;

	private final TestConfigHelper configHelper = new TestConfigHelper();
	public static SampleStore sampleStore = null;
	private Collection<SampleOrg> testSampleOrgs;
	
	Map<String, Properties> clientTLSProperties = new HashMap<>();
	File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");

    String testTxID = null;

    public BlockChainToolJavaSDK() {
		
	}
	
	/**
	 * 获取实例
	 */
	public static BlockChainToolJavaSDK getInstance() {
		return instance;
	}
	
	public void checkConfig() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException, MalformedURLException, org.hyperledger.fabric_ca.sdk.exception.InvalidArgumentException {

		resetConfig(); //重置配置文件
        configHelper.customizeConfig();

        testSampleOrgs = testConfig.getIntegrationTestsSampleOrgs(); //获取testConfig配置的组织
        //Set up hfca for each sample org
        for(SampleOrg sampleOrg: testSampleOrgs) {
            System.out.println(sampleOrg.toString());
        }
        for (SampleOrg sampleOrg : testSampleOrgs) { //设置颁发证书的CA证书机构
            String caName = sampleOrg.getCAName(); //Try one of each name and no name.
            if (caName != null && !caName.isEmpty()) {
                sampleOrg.setCAClient(HFCAClient.createNewInstance(caName, sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
            } else {
                sampleOrg.setCAClient(HFCAClient.createNewInstance(sampleOrg.getCALocation(), sampleOrg.getCAProperties()));
            }
        }
        sampleStore = new SampleStore(sampleStoreFile);
    }
	
    public Map<String, String> setup() throws Exception {
        //Persistence is not part of SDK. Sample file store is for demonstration purposes only!
        //   MUST be replaced with more robust application implementation  (Database, LDAP)
        Map<String, String> resultMap = new HashMap<>();
        if (sampleStoreFile.exists()) { //For testing start fresh
            sampleStoreFile.delete();
        }
        sampleStore = new SampleStore(sampleStoreFile);

        for (SampleOrg sampleOrg : testSampleOrgs) {
            final String sampleOrgName = sampleOrg.getName();
            //获得组织的命名域
            final String sampleOrgDomainName = sampleOrg.getDomainName();
            SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                    Util.findFileSk(Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/",
                            sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                    Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                            format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());

            //设置节点管理员（一个特殊的用户，可以创建通道，加入节点以及安装链码）
            sampleOrg.setPeerAdmin(peerOrgAdmin); //A special user that can create channels, join peers and install chaincode
        }
        //enrollUsersSetup(sampleStore); //This enrolls users with fabric ca and setups sample store to get users later.
        resultMap = runFabric(sampleStore); //Runs Fabric tests with constructing channels, joining peers, exercising chaincode
        return  resultMap;
    }
	
	/**
	 * Will register and enroll users persisting them to samplestore.
	 * 
	 * @throws Exception
	 */
	public void enrollUsersSetup(final SampleStore sampleStore) throws Exception{
			
		//获得所有组织的所有用户
		for (SampleOrg sampleOrg : testSampleOrgs) {
            //通过组织信息获得CA客户端
            HFCAClient ca = sampleOrg.getCAClient();
            //获得组织名称
            final String orgName = sampleOrg.getName();
            //获得组织证书id
            final String mspid = sampleOrg.getMSPID();
            //设置CA客户端证书套件
            ca.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

            if (testConfig.isRunningFabricTLS()) {
                //This shows how to get a client TLS certificate from Fabric CA
                // we will use one client TLS certificate for orderer peers etc.
                final EnrollmentRequest enrollmentRequestTLS = new EnrollmentRequest();
                enrollmentRequestTLS.addHost("localhost");
                enrollmentRequestTLS.setProfile("tls");
                final Enrollment enroll = ca.enroll("admin", "adminpw", enrollmentRequestTLS);
                final String tlsCertPEM = enroll.getCert();
                final String tlsKeyPEM = getPEMStringFromPrivateKey(enroll.getKey());

                final Properties tlsProperties = new Properties();

                tlsProperties.put("clientKeyBytes", tlsKeyPEM.getBytes(UTF_8));
                tlsProperties.put("clientCertBytes", tlsCertPEM.getBytes(UTF_8));
                clientTLSProperties.put(sampleOrg.getName(), tlsProperties);
                //Save in samplestore for follow on tests.
                sampleStore.storeClientPEMTLCertificate(sampleOrg, tlsCertPEM);
                sampleStore.storeClientPEMTLSKey(sampleOrg, tlsKeyPEM);
            }


            //获得组织管理员（admin）
            SampleUser admin = sampleStore.getMember(TEST_ADMIN_NAME, orgName);
            //如果未进行登记
            if (!admin.isEnrolled()) {  //Preregistered admin only needs to be enrolled with Fabric caClient.
                //预注册管理员只需要在fabric CA客户端进行登记
                admin.setEnrollment(ca.enroll(admin.getName(), "adminpw"));
                admin.setMspId(mspid);
            }
            //设置管理员身份
            sampleOrg.setAdmin(admin); // The admin of this org --
            //获得普通用户
            SampleUser user = sampleStore.getMember(TESTUSER_1_NAME, sampleOrg.getName());
            //如果未注册
            if (!user.isRegistered()) {  // users need to be registered AND enrolled
                //生成注册请求
                RegistrationRequest rr = new RegistrationRequest(user.getName(), "org1.department1");
                //设置用户密码（注册请求发出去后，CA会返回一个密码）
                user.setEnrollmentSecret(ca.register(rr, admin));
            }
            //如果未登记
            if (!user.isEnrolled()) {
                //进行登记操作
                user.setEnrollment(ca.enroll(user.getName(), user.getEnrollmentSecret()));
                user.setMspId(mspid);
            }
            //组织内添加用户
            sampleOrg.addUser(user); //Remember user belongs to this Org
            //获得示例组织名称
            final String sampleOrgName = sampleOrg.getName();
            //获得组织的命名域
            final String sampleOrgDomainName = sampleOrg.getDomainName();

            // src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/
            //使用证书文件生成用户信息
            SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                    Util.findFileSk(Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/",
                            sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                    Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                            format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());
            //设置节点管理员（一个特殊的用户，可以创建通道，加入节点以及安装链码）
            sampleOrg.setPeerAdmin(peerOrgAdmin); //A special user that can create channels, join peers and install chaincode

        }
	}
	
	/**
	 * 链码、通道、peer节点初始化等操作
	 * 
	 * @throws Exception
	 */
    public Map<String, String> runFabric(final SampleStore sampleStore) throws Exception {

        Map<String, String> resultMap = new HashMap<>();
        ////////////////////////////
        // Setup client

        //Create instance of client.
        client = HFClient.createNewInstance();
        //生成并设置加密套件
        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        ////////////////////////////
        //Construct and run the channels
        //获得组织配置信息
        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
        //构建信道（信道名称，客户端[cli]，组织）
       // Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg);
        //sampleStore.saveChannel(fooChannel);
        //启动信道
       // resultMap = runChannel(client, fooChannel, true, sampleOrg, 0);

//        //停止channel（停止客户端，不停止环境）
//        fooChannel.shutdown(true); // Force foo channel to shutdown clean up resources.
//
//        sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg2");
//        Channel barChannel = constructChannel(BAR_CHANNEL_NAME, client, sampleOrg);
//        /**
//         * sampleStore.saveChannel uses {@link Channel#serializeChannel()}
//         */
//        sampleStore.saveChannel(barChannel);
//        runChannel(client, barChannel, true, sampleOrg, 100); //run a newly constructed bar channel with different b value!
//        //let bar channel just shutdown so we have both scenarios.
//
//        out("\nTraverse the blocks for chain %s ", barChannel.getName());
//
//        blockWalker(client, barChannel);
//
        out("That's all folks!");
        return resultMap;
    }
	
    /**
     * 安装链码、实例化链码
     * 
     * @param name
     * @param client
     * @param sampleOrg
     * @return
     * @throws Exception
     */
	public Channel constructChannel(String name, HFClient client, SampleOrg sampleOrg) throws Exception {
        ////////////////////////////
        //Construct the channel
        //

        out("Constructing channel %s", name);

        //boolean doPeerEventing = false;
        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && BAR_CHANNEL_NAME.equals(name);
//	        boolean doPeerEventing = !testConfig.isRunningAgainstFabric10() && FOO_CHANNEL_NAME.equals(name);
        //Only peer Admin org
        //只支持节点管理员操作
        client.setUserContext(sampleOrg.getPeerAdmin());

        Collection<Orderer> orderers = new LinkedList<>();
        //循环读取排序服务器
        for (String orderName : sampleOrg.getOrdererNames()) {
            //根据排序节点名称，获得其配置信息
            Properties ordererProperties = testConfig.getOrdererProperties(orderName);

            //example of setting keepAlive to avoid timeouts on inactive http2 connections.
            // Under 5 minutes would require changes to server side to accept faster ping rates.
            //设置保持连接时长：5分钟
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            //设置连接超时时长：8秒钟
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveWithoutCalls", new Object[] {true});

            //在新建的集合中增加排序节点
            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    ordererProperties));
        }

        //Just pick the first orderer in the list to create the channel.
        //选择第一个排序节点进行创建通道
        Orderer anOrderer = orderers.iterator().next();
        //从排序节点集合中移除已经在使用的节点？？？？
        orderers.remove(anOrderer);
        //通过读取channel.tx文件，生成信道配置对象
        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(TEST_FIXTURES_PATH + "/sdkintegration/e2e-2Orgs/" + TestConfig.FAB_CONFIG_GEN_VERS + "/" + name + ".tx"));

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
        //创建信道对象，只有一个签名，就是组织节点管理员。如果创建信道策略需要更多的签名，那么他们必须添加
        Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));

        out("Created channel %s", name);

        boolean everyother = true; //test with both cases when doing peer eventing.
        //获得组织内节点信息
        for (String peerName : sampleOrg.getPeerNames()) {
            //获取节点位置
            String peerLocation = sampleOrg.getPeerLocation(peerName);
            //获得节点属性
            Properties peerProperties = testConfig.getPeerProperties(peerName); //test properties for peer.. if any.
            if (peerProperties == null) {
                peerProperties = new Properties();
            }


            //Example of setting specific options on grpc's NettyChannelBuilder
            //设置节点最大消息传输量：9000000，9M？
            peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);
            //通过客户端生成节点对象，保持节点连接
            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
            //在新的信道中加入生成的节点
            if (doPeerEventing && everyother) {
                newChannel.joinPeer(peer, createPeerOptions()); //Default is all roles.
            } else {
                // Set peer to not be all roles but eventing.
                newChannel.joinPeer(peer, createPeerOptions().setPeerRoles(PeerRole.NO_EVENT_SOURCE));
            }
            out("Peer %s joined channel %s", peerName, name);
            everyother = !everyother;
        }

        //加载剩下的排序节点（这里已经排除了已经占用了的排序节点）
        for (Orderer orderer : orderers) { //add remaining orderers if any.
            newChannel.addOrderer(orderer);
        }
        //获得组织内事件记录节点集合
        for (String eventHubName : sampleOrg.getEventHubNames()) {
            //从配置中获取事件记录属性
            final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);
            //增加保持连接事件以及连接超时时间设置
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});

            //创建事件记录对象
            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    eventHubProperties);
            //将事件记录对象加入到信道中
            newChannel.addEventHub(eventHub);
        }
        //信道实例化
        newChannel.initialize();

        out("Finished initialization channel %s", name);

        return newChannel;

    }
	
	/**
	 * 安装链码、实例化链码
	 * @param client
	 * @param channel
	 * @param installChaincode
	 * @param sampleOrg
	 * @param delta
	 */
    Map<String,String> runChannel(HFClient client, Channel channel, boolean installChaincode, SampleOrg sampleOrg, int delta) {

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
        Map<String, String> resultMap = new HashMap<>();
        try {
            //获得信道名称
            final String channelName = channel.getName();
            //检查信道名称是否正确
            boolean isFooChain = FOO_CHANNEL_NAME.equals(channelName);
            out("Running channel %s", channelName);
            //获取信道内排序节点集合
            Collection<Orderer> orderers = channel.getOrderers();
            //声明链码标识
            final ChaincodeID chaincodeID;
            //提案结果（请求结果）
            Collection<ProposalResponse> responses;
            //成功结果集合
            Collection<ProposalResponse> successful = new LinkedList<>();
            //失败结果集合
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
            //生成链码标识对象
            ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                    .setVersion(CHAIN_CODE_VERSION);
            if (null != CHAIN_CODE_PATH) {
                chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);
            }
            chaincodeID = chaincodeIDBuilder.build();

            ///////////////////////////////////////////////////////////////////////////////////////////
            //如果需要安装链码
            if (installChaincode) {
                ////////////////////////////
                // Install Proposal Request
                //
                //设置客户端用户内容（当前组织的节点管理员）
                client.setUserContext(sampleOrg.getPeerAdmin());

                out("Creating install proposal");
                //构建安装提案请求
                InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
                //设置链码标识
                installProposalRequest.setChaincodeID(chaincodeID);
                //如果是foo链码
                if (isFooChain) {
                    // on foo chain install from directory.

                    ////For GO language and serving just a single user, chaincodeSource is mostly likely the users GOPATH
                    //设置链码安装路径（由代码跑的位置来看，可以将链码放在本地进行安装）
                    installProposalRequest.setChaincodeSourceLocation(Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile());
                } else {
                    // On bar chain install from an input stream.
                    //从输入流安装bar链码
                    if (CHAIN_CODE_LANG.equals(Type.GO_LANG)) {

                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH, "src", CHAIN_CODE_PATH).toFile()),
                                Paths.get("src", CHAIN_CODE_PATH).toString()));
                    } else {
                        installProposalRequest.setChaincodeInputStream(Util.generateTarGzInputStream(
                                (Paths.get(TEST_FIXTURES_PATH, CHAIN_CODE_FILEPATH).toFile()),
                                "src"));
                    }
                }
                //设置链码版本
                installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);
                installProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);

                out("Sending install proposal");

                ////////////////////////////
                // only a client from the same org as the peer can issue an install request
                // 只有来自同一个组织节点的客户端才能发起安装请求
                //安装提案数量？
                int numInstallProposal = 0;
                //    Set<String> orgs = orgPeers.keySet();
                //   for (SampleOrg org : testSampleOrgs) {
                //获得组织内节点集合
                Collection<Peer> peers = channel.getPeers();
                //应该发送的提案数量
                numInstallProposal = numInstallProposal + peers.size();
                //发送安装提案，获得响应集合
                responses = client.sendInstallProposal(installProposalRequest, peers);
                //遍历返回结果集合
                for (ProposalResponse response : responses) {
                    //如果提案成功
                    if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                        //将该结果纳入成功的集合
                        out("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                        successful.add(response);
                    } else {
                        //加入失败集合
                        failed.add(response);
                    }
                }

                //   }
                out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size());
                //如果有失败的节点，取出错误信息，并返回异常：没有足够的背书节点
                if (failed.size() > 0) {
                    ProposalResponse first = failed.iterator().next();
                    System.err.println("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
                    resultMap.put("code","error");
                    resultMap.put("message","Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
                    return resultMap;
                }
            }
            ///////////////////////////////////////////////////////////////////////////////////////////


            //   client.setUserContext(sampleOrg.getUser(TEST_ADMIN_NAME));
            //  final ChaincodeID chaincodeID = firstInstallProposalResponse.getChaincodeID();
            // Note installing chaincode does not require transaction no need to
            // send to Orderers

            ///////////////
            //// Instantiate chaincode.

            //构建实例化提案请求
            InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
            //设置请求等待时间
            instantiateProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
            //设置链码标识
            instantiateProposalRequest.setChaincodeID(chaincodeID);
            instantiateProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
            //设置实例化方法名称
            instantiateProposalRequest.setFcn("init");
            //设置实例化参数列表
            instantiateProposalRequest.setArgs(new String[] {"a", "500", "b", "" + (200 + delta)});
            Map<String, byte[]> tm = new HashMap<>();
            tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
            tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
            //设置提案请求参数
            instantiateProposalRequest.setTransientMap(tm);

            /*
              policy OR(Org1MSP.member, Org2MSP.member) meaning 1 signature from someone in either Org1 or Org2
              See README.md Chaincode endorsement policies section for more details.
                                              指定背书策略：Org1或者Org2其中一个进行背书签名即可
            */
            //生成背书策略
            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            //从配置文件中加载背书策略（这里的文件貌似是通用的，意思是不是一个节点上的链码背书策略都是相同的？）
            chaincodeEndorsementPolicy.fromYamlFile(new File(TEST_FIXTURES_PATH + "/sdkintegration/chaincodeendorsementpolicy.yaml"));
            //在实例化请求中加载该背书策略
            instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

            out("Sending instantiateProposalRequest to all peers with arguments: a and b set to 100 and %s respectively", "" + (200 + delta));
            //清除之前的返回结果集存储
            successful.clear();
            failed.clear();
            //如果信道是foo
            if (isFooChain) {  //Send responses both ways with specifying peers and by using those on the channel.
                //发送实例化提案（向所有信道内的节点）
                responses = channel.sendInstantiationProposal(instantiateProposalRequest, channel.getPeers());
            } else {
                //发送实例化提案（信道内所有节点）
                responses = channel.sendInstantiationProposal(instantiateProposalRequest);
            }
            //遍历响应结果集（将响应结果分类存储）
            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                    out("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                } else {
                    failed.add(response);
                }
            }
            out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());
            //如果有失败提案，则返回失败信息，以及帮助提示
            if (failed.size() > 0) {
                for (ProposalResponse fail : failed) {

                    out("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + fail.getMessage() + ", on peer" + fail.getPeer());

                }
                ProposalResponse first = failed.iterator().next();
                System.err.println("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
                resultMap.put("code","error");
                resultMap.put("message","Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
                return resultMap;
            }

            out("Running for Channel %s done", channelName);

            ///////////////
            /// Send instantiate transaction to orderer
            out("Sending instantiateTransaction to orderer with a and b set to 100 and %s respectively", "" + (200 + delta));

            //Specify what events should complete the interest in this transaction. This is the default
            // for all to complete. It's possible to specify many different combinations like
            //any from a group, all from one group and just one from another or even None(NOfEvents.createNoEvents).
            // See. Channel.NOfEvents
            Channel.NOfEvents nOfEvents = createNofEvents();
            if (!channel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)).isEmpty()) {
                nOfEvents.addPeers(channel.getPeers(EnumSet.of(PeerRole.EVENT_SOURCE)));
            }
            if (!channel.getEventHubs().isEmpty()) {
                nOfEvents.addEventHubs(channel.getEventHubs());
            }
            //向排序节点发送交易信息
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

//                try {
//                    assertEquals(blockEvent.getChannelId(), channel.getName());
//                    successful.clear();
//                    failed.clear();
//
//                    client.setUserContext(sampleOrg.getUser(TESTUSER_1_NAME));
//
//                    ///////////////
//                    /// Send transaction proposal to all peers
//                    //发送交易提案至所有节点
//                    //生成交易提案
//                    TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
//                    //设置链码标识
//                    transactionProposalRequest.setChaincodeID(chaincodeID);
//                    transactionProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
//                    //transactionProposalRequest.setFcn("invoke");
//                    //设置调用链码方法
//                    transactionProposalRequest.setFcn("move");
//                    //设置提案等待时间
//                    transactionProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
//                    //设置参数
//                    transactionProposalRequest.setArgs("a", "b", "100");
//
//                    Map<String, byte[]> tm2 = new HashMap<>();
//                    tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8)); //Just some extra junk in transient map
//                    tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8)); // ditto
//                    tm2.put("result", ":)".getBytes(UTF_8));  // This should be returned see chaincode why.
//                    tm2.put(EXPECTED_EVENT_NAME, EXPECTED_EVENT_DATA);  //This should trigger an event see chaincode why.
//
//                    //设置交易参数
//                    transactionProposalRequest.setTransientMap(tm2);
//
//                    out("sending transactionProposal to all peers with arguments: move(a,b,100)");
//                    //发送交易提案，并获得响应集合
//                    Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers());
//                    //遍历响应并归类
//                    for (ProposalResponse response : transactionPropResp) {
//                        if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
//                            out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
//                            successful.add(response);
//                        } else {
//                            failed.add(response);
//                        }
//                    }
//
//                    // Check that all the proposals are consistent with each other. We should have only one set
//                    // where all the proposals above are consistent. Note the when sending to Orderer this is done automatically.
//                    //  Shown here as an example that applications can invoke and select.
//                    // See org.hyperledger.fabric.sdk.proposal.consistency_validation config property.
//                    //从返回结果中提取拥有一致提案结果的集合（校验所有背书节点的背书结果是否一致）
//                    Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
//                    if (proposalConsistencySets.size() != 1) {
//                        //从返回结果中提取拥有一致提案结果的集合（校验所有背书节点的背书结果是否一致）
//                        fail(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
//                    }
//
//                    out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
//                            transactionPropResp.size(), successful.size(), failed.size());
//                    if (failed.size() > 0) {
//                        //如果有背书失败情况，则退出（3f+1）
//                        ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
//                        fail("Not enough endorsers for invoke(move a,b,100):" + failed.size() + " endorser error: " +
//                                firstTransactionProposalResponse.getMessage() +
//                                ". Was verified: " + firstTransactionProposalResponse.isVerified());
//                    }
//                    out("Successfully received transaction proposal responses.");
//                    //提取提案结果的第一个解（此处所有解应该相同，如果不同，在前面的校验已经处理过了，不会走到这一步）
//                    ProposalResponse resp = successful.iterator().next();
//                    //提取提案结果中的返回数据
//                    byte[] x = resp.getChaincodeActionResponsePayload(); // This is the data returned by the chaincode.
//                    //提取提案结果中的返回数据
//                    String resultAsString = null;
//                    if (x != null) {
//                        resultAsString = new String(x, "UTF-8");
//                    }
//                    assertEquals(":)", resultAsString);
//
//                    assertEquals(200, resp.getChaincodeActionResponseStatus()); //Chaincode's status.
//                    //获取事务读写集信息
//                    TxReadWriteSetInfo readWriteSetInfo = resp.getChaincodeActionResponseReadWriteSetInfo();
//                    //See blockwalker below how to transverse this
//                    //判断非空
//                    assertNotNull(readWriteSetInfo);
//                    assertTrue(readWriteSetInfo.getNsRwsetCount() > 0);
//                    //从返回信息中获取链码标识
//                    ChaincodeID cid = resp.getChaincodeID();
//                    assertNotNull(cid);
//                    final String path = cid.getPath();
//                    if (null == CHAIN_CODE_PATH) {
//                        assertTrue(path == null || "".equals(path));
//
//                    } else {
//
//                        assertEquals(CHAIN_CODE_PATH, path);
//
//                    }
//
//                    assertEquals(CHAIN_CODE_NAME, cid.getName());
//                    assertEquals(CHAIN_CODE_VERSION, cid.getVersion());
//
//                    ////////////////////////////
//                    // Send Transaction Transaction to orderer
//                    out("Sending chaincode transaction(move a,b,100) to orderer.");
//                    //向排序节点发送交易请求
//                    return channel.sendTransaction(successful).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);
//
//                } catch (Exception e) {
//                    out("Caught an exception while invoking chaincode");
//                    e.printStackTrace();
//                    fail("Failed invoking chaincode with error : " + e.getMessage());
//                }

                return null;

            }).thenApply(transactionEvent -> {
//                try {
//
//                    waitOnFabric(0);
//
//                    assertTrue(transactionEvent.isValid()); // must be valid to be here.
//                    out("Finished transaction with transaction id %s", transactionEvent.getTransactionID());
//                    testTxID = transactionEvent.getTransactionID(); // used in the channel queries later
//
//                    ////////////////////////////
//                    // Send Query Proposal to all peers
//                    //发送查询请求至所有节点
//                    String expect = "" + (300 + delta);
//                    out("Now query chaincode for the value of b.");
//                    //开始构建请求对象
//                    QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
//                    queryByChaincodeRequest.setArgs(new String[] {"b"});
//                    queryByChaincodeRequest.setFcn("query");
//                    queryByChaincodeRequest.setChaincodeID(chaincodeID);
//
//                    Map<String, byte[]> tm2 = new HashMap<>();
//                    tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
//                    tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
//                    queryByChaincodeRequest.setTransientMap(tm2);
//
//                    Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
//                    for (ProposalResponse proposalResponse : queryProposals) {
//                        if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
//                            fail("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
//                                    ". Messages: " + proposalResponse.getMessage()
//                                    + ". Was verified : " + proposalResponse.isVerified());
//                        } else {
//                            String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
//                            out("Query payload of b from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
//                            assertEquals(payload, expect);
//                        }
//                    }
//
//                    return null;
//                } catch (Exception e) {
//                    out("Caught exception while running query");
//                    e.printStackTrace();
//                    fail("Failed during chaincode query with error : " + e.getMessage());
//                }
//
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
        } catch (Exception e) {
            out("Caught an exception running channel %s", channel.getName());
            e.printStackTrace();
            System.err.println("Test failed with error : " + e.getMessage());
            resultMap.put("code","error");
            resultMap.put("messgae","Test failed with error : " + e.getMessage());
            return resultMap;
        }
        resultMap.put("code","success");
        resultMap.put("messgae","");
        return resultMap;
    }

    private void waitOnFabric(int additional) {
        //NOOP today

    }

    /**
     *
     * @return
     */
    public Channel getChannelInfo() throws InvalidArgumentException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, InstantiationException, CryptoException, IllegalAccessException {

//        HFClient client = HFClient.createNewInstance();
//        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        if (client.getCryptoSuite()==null){
            client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        }
        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");
//        client.setUserContext(sampleOrg.getUser(TESTUSER_1_NAME));
        client.setUserContext(sampleOrg.getPeerAdmin());
        Channel channel = null;
//        channel = sampleStore.getChannel(client,"foo").initialize();
        channel = client.getChannel("mychannel");
        return channel;
    }

    /**
     * Query by block number.
     *
     * @return
     */
	public BlockInfo queryBlockByNumber(int blockNumber){
	    BlockInfo blockInfo = null;
        try {
            Channel channel = getChannelInfo();
            blockInfo = channel.queryBlockByNumber(blockNumber);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return blockInfo;
    }

    /**
     * Query by block hash.
     *
     * @return
     */
    public BlockInfo queryBlockByHash( byte[] hashQuery){
        BlockInfo blockInfo = null;
        try {
            Channel channel = getChannelInfo();
            blockInfo = channel.queryBlockByHash(hashQuery);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return blockInfo;
    }

    /**
     *  Query block by TxID.
     *
     * @return
     */
    public BlockInfo queryBlockByTransactionID(String testTxID){
        BlockInfo blockInfo = null;
        try {
            Channel channel = getChannelInfo();
            blockInfo = channel.queryBlockByTransactionID(testTxID);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return blockInfo;
    }

    /**
     *  query block by TxID.
     *
     * @return
     */
    public TransactionInfo queryTransactionByID(String testTxID){
        TransactionInfo transactionInfo = null;
        try {
            Channel channel = getChannelInfo();
            transactionInfo = channel.queryTransactionByID(testTxID);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return transactionInfo;
    }

    /**
       * 执行智能合约
       *
       * @param fcn
       *            方法名
       * @param args
       *            参数数组
       * @return
       * @throws InvalidArgumentException
       * @throws ProposalException
       * @throws InterruptedException
       * @throws ExecutionException
       * @throws TimeoutException
       * @throws CryptoException
       * @throws InvalidKeySpecException
       * @throws NoSuchProviderException
       * @throws NoSuchAlgorithmException
       */
    public Map<String, String> invoke(String fcn, String[] args)
            throws InvalidArgumentException, ProposalException, InterruptedException, ExecutionException, TimeoutException, CryptoException, IOException, ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {

        final ChaincodeID chaincodeID;
        ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(CHAIN_CODE_VERSION);
        if (null != CHAIN_CODE_PATH) {
            chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);
        }
        chaincodeID = chaincodeIDBuilder.build();

        Map<String, String> resultMap = new HashMap<>();

        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        /// Send transaction proposal to all peers
        TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
        transactionProposalRequest.setChaincodeID(chaincodeID);
        transactionProposalRequest.setChaincodeLanguage(CHAIN_CODE_LANG);
        transactionProposalRequest.setFcn(fcn);
        transactionProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
        transactionProposalRequest.setArgs(args);

        Map<String, byte[]> tm2 = new HashMap<>();
        tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
        tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
        tm2.put("result", ":)".getBytes(UTF_8));
        tm2.put(EXPECTED_EVENT_NAME, EXPECTED_EVENT_DATA);
        transactionProposalRequest.setTransientMap(tm2);

        out("sending transactionProposal to all peers with arguments: "+ fcn + Arrays.toString(args));
        Channel channel = getChannelInfo();
        Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers());
        for (ProposalResponse response : transactionPropResp) {
            if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                successful.add(response);
            } else {
                failed.add(response);
            }
        }

        Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
        if (proposalConsistencySets.size() != 1) {
            System.err.println(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
        }
        out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
                transactionPropResp.size(), successful.size(), failed.size());
        if (failed.size() > 0) {
            ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
            System.err.println("Not enough endorsers for inspect:" + fcn + args + failed.size() + " endorser error: " + firstTransactionProposalResponse.getMessage() + ". Was verified: "
                         + firstTransactionProposalResponse.isVerified());
            resultMap.put("code", "error");
            resultMap.put("data", firstTransactionProposalResponse.getMessage());
            return resultMap;
        } else {
            out("Successfully received transaction proposal responses.");
            ProposalResponse resp = successful.iterator().next();
            byte[] x = resp.getChaincodeActionResponsePayload();
            String resultAsString = null;
            if (x != null) {
                resultAsString = new String(x, "UTF-8");
            }
            System.out.println("resultAsString = " + resultAsString);
            channel.sendTransaction(successful).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);
            resultMap.put("code", "success");
            resultMap.put("data", resultAsString);
            return resultMap;
        }
         //        channel.sendTransaction(successful).thenApply(transactionEvent -> {
       //            if (transactionEvent.isValid()) {
       //                log.info("Successfully send transaction proposal to orderer. Transaction ID: " + transactionEvent.getTransactionID());
       //            } else {
       //                log.info("Failed to send transaction proposal to orderer");
       //            }
       //            // chain.shutdown(true);
       //            return transactionEvent.getTransactionID();
       //        }).get(chaincode.getInvokeWatiTime(), TimeUnit.SECONDS);
    }

    /**
      * 查询智能合约
      *
      * @param fcn
      *            方法名
      * @param args
      *            参数数组
      * @return
      * @throws InvalidArgumentException
      * @throws ProposalException
      * @throws IOException
      * @throws TransactionException
      * @throws CryptoException
      * @throws InvalidKeySpecException
      * @throws NoSuchProviderException
      * @throws NoSuchAlgorithmException
      */
    public Map<String, String> query(String fcn, String[] args) throws InvalidArgumentException, ProposalException, CryptoException, TransactionException, IOException, ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {

        final ChaincodeID chaincodeID;
        ChaincodeID.Builder chaincodeIDBuilder = ChaincodeID.newBuilder().setName(CHAIN_CODE_NAME)
                .setVersion(CHAIN_CODE_VERSION);
        if (null != CHAIN_CODE_PATH) {
            chaincodeIDBuilder.setPath(CHAIN_CODE_PATH);
        }
        chaincodeID = chaincodeIDBuilder.build();

        Map<String, String> resultMap = new HashMap<>();
        String payload = "";
        QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
        queryByChaincodeRequest.setArgs(args);
        queryByChaincodeRequest.setFcn(fcn);
        queryByChaincodeRequest.setChaincodeID(chaincodeID);

        Map<String, byte[]> tm2 = new HashMap<>();
        tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
        tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
        queryByChaincodeRequest.setTransientMap(tm2);

        Channel channel = getChannelInfo();

        Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
        for (ProposalResponse proposalResponse : queryProposals) {
           if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
               System.err.println("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() + ". Messages: "
                             + proposalResponse.getMessage() + ". Was verified : " + proposalResponse.isVerified());
                resultMap.put("code", "error");
                resultMap.put("user", args[0]);
                resultMap.put("data", "Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() + ". Messages: "
                            + proposalResponse.getMessage() + ". Was verified : " + proposalResponse.isVerified());
            } else {
                payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                out("Query payload of %s from peer %s returned %s",args[0],proposalResponse.getPeer().getName(),payload);
                out("" + payload);
                resultMap.put("code", "success");
                resultMap.put("user",args[0]);
                resultMap.put("data", payload);
            }
        }
        return resultMap;
    }

    private static boolean checkInstalledChaincode(HFClient client, Peer peer, String ccName, String ccPath, String ccVersion) throws InvalidArgumentException, ProposalException {

        out("Checking installed chaincode: %s, at version: %s, on peer: %s", ccName, ccVersion, peer.getName());
        List<Query.ChaincodeInfo> ccinfoList = client.queryInstalledChaincodes(peer);

        boolean found = false;

        for (Query.ChaincodeInfo ccifo : ccinfoList) {

            if (ccPath != null) {
                found = ccName.equals(ccifo.getName()) && ccPath.equals(ccifo.getPath()) && ccVersion.equals(ccifo.getVersion());
                if (found) {
                    break;
                }
            }

            found = ccName.equals(ccifo.getName()) && ccVersion.equals(ccifo.getVersion());
            if (found) {
                break;
            }

        }

        return found;
    }

    private static boolean checkInstantiatedChaincode(Channel channel, Peer peer, String ccName, String ccPath, String ccVersion) throws InvalidArgumentException, ProposalException {
        out("Checking instantiated chaincode: %s, at version: %s, on peer: %s", ccName, ccVersion, peer.getName());
        List<Query.ChaincodeInfo> ccinfoList = channel.queryInstantiatedChaincodes(peer);

        boolean found = false;

        for (Query.ChaincodeInfo ccifo : ccinfoList) {

            if (ccPath != null) {
                found = ccName.equals(ccifo.getName()) && ccPath.equals(ccifo.getPath()) && ccVersion.equals(ccifo.getVersion());
                if (found) {
                    break;
                }
            }

            found = ccName.equals(ccifo.getName()) && ccVersion.equals(ccifo.getVersion());
            if (found) {
                break;
            }

        }

        return found;
    }

    public String queryInstantiatedChaincodes() {

        List<Query.ChaincodeInfo> ccinfoList = null;
        Channel channel = null;
        try {
            channel = getChannelInfo();
            client.setUserContext(testConfig.getIntegrationTestsSampleOrg("peerOrg1").getPeerAdmin());
            for (Peer peer : channel.getPeers()){
                ccinfoList = channel.queryInstantiatedChaincodes(peer);
            }
        }  catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getMessage());
            return e.getMessage();
        }
        return String.valueOf(ccinfoList.size());
    }

    public String queryInstalledChaincodes() {

        List<Query.ChaincodeInfo> ccinfoList = null;
        try {
            Channel channel = getChannelInfo();
            for (Peer peer : channel.getPeers()){
                client.setUserContext( testConfig.getIntegrationTestsSampleOrg("peerOrg1").getPeerAdmin());
                ccinfoList = client.queryInstalledChaincodes(peer);
            }
        }  catch (Exception e) {
            e.printStackTrace();
            System.err.println(e.getMessage());
            return e.getMessage();
        }
        return String.valueOf(ccinfoList.size());
    }

	static void out(String format, Object... args) {

        System.err.flush();
        System.out.flush();

        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();

    }
	
    static String getPEMStringFromPrivateKey(PrivateKey privateKey) throws IOException {
        StringWriter pemStrWriter = new StringWriter();
        PEMWriter pemWriter = new PEMWriter(pemStrWriter);

        pemWriter.writeObject(privateKey);

        pemWriter.close();

        return pemStrWriter.toString();
    }

    public boolean isInteger(String str){
        int i;
        try {
            i = Integer.parseInt(str);
        } catch (NumberFormatException ex) {
            return false;
        }
        return true;
    }
    public static String printableString(final String string) {
        int maxLogStringLength = 64;
        if (string == null || string.length() == 0) {
            return string;
        }

        String ret = string.replaceAll("[^\\p{Print}]", "?");

        ret = ret.substring(0, Math.min(ret.length(), maxLogStringLength)) + (ret.length() > maxLogStringLength ? "..." : "");

        return ret;

    }
}
