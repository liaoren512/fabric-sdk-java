package org.hyperledger.fabric.sdkintegration;

import org.bouncycastle.openssl.PEMWriter;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric.sdk.testutils.TestConfig;
import org.hyperledger.fabric_ca.sdk.EnrollmentRequest;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.HFCAInfo;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.hyperledger.fabric_ca.sdk.exception.InvalidArgumentException;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hyperledger.fabric.sdk.Channel.PeerOptions.createPeerOptions;
import static org.hyperledger.fabric.sdk.testutils.TestUtils.resetConfig;
import static org.junit.Assert.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnrollTest {

    static String testName = "End2endIT";
    private static final TestConfigHelper configHelper = new TestConfigHelper();
    private static Collection<SampleOrg> testSampleOrgs;
    public static final TestConfig testConfig = TestConfig.getConfig();
    static SampleStore sampleStore = null;
    private static final String TEST_ADMIN_NAME = "admin";
    private static final String TESTUSER_1_NAME = "user1";
    private static final String FOO_CHANNEL_NAME = "foo";
    private static final String BAR_CHANNEL_NAME = "bar";
    private static final String TEST_FIXTURES_PATH = "src/test/fixture";

    public static void main(String[] args) throws Exception {

        checkConfig();
        setup();
    }

    public static void checkConfig() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException, MalformedURLException, InvalidArgumentException {
        out("\n\n\nRUNNING: %s.\n", testName);
        //   configHelper.clearConfig();
        //   assertEquals(256, Config.getConfig().getSecurityLevel());
        resetConfig(); //先重置配置文件org.hyperledger.fabric.sdk.helper.Config，config主要是的fabric需要的配置项
        configHelper.customizeConfig(); //调用命令行输入的指定变量，覆盖掉上面的Config配置

        testSampleOrgs = testConfig.getIntegrationTestsSampleOrgs(); //获取testConfig配置的组织，TestConfig在End2endIT已经被new出来，里面也有大量的测试配置项
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
    }

    static Map<String, Properties> clientTLSProperties = new HashMap<>();

    static File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");

    public static void setup() throws Exception{

        if (sampleStoreFile.exists()) { //For testing start fresh
            sampleStoreFile.delete();
        }
        sampleStore = new SampleStore(sampleStoreFile);
        enrollUsersSetup(sampleStore);
        //runFabricTest(sampleStore);
    }

    public static void enrollUsersSetup(SampleStore sampleStore) throws Exception {

        // get users for all orgs

        for (SampleOrg sampleOrg : testSampleOrgs) {

            HFCAClient ca = sampleOrg.getCAClient();

            final String orgName = sampleOrg.getName();
            final String mspid = sampleOrg.getMSPID();
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

            HFCAInfo info = ca.info(); //just check if we connect at all.
            assertNotNull(info);
            String infoName = info.getCAName();
            if (infoName != null && !infoName.isEmpty()) {
                assertEquals(ca.getCAName(), infoName);
            }

            SampleUser admin = sampleStore.getMember(TEST_ADMIN_NAME, orgName);
            if (!admin.isEnrolled()) {  //Preregistered admin only needs to be enrolled with Fabric caClient.
                admin.setEnrollment(ca.enroll(admin.getName(), "adminpw"));
                admin.setMspId(mspid);
            }

            sampleOrg.setAdmin(admin); // The admin of this org --

            SampleUser user = sampleStore.getMember(TESTUSER_1_NAME, sampleOrg.getName());
            if (!user.isRegistered()) {  // users need to be registered AND enrolled
                RegistrationRequest rr = new RegistrationRequest(user.getName(), "org1.department1");
                user.setEnrollmentSecret(ca.register(rr, admin));

                //-------------------------------------------------------------
            }
            if (!user.isEnrolled()) {
                user.setEnrollment(ca.enroll(user.getName(), user.getEnrollmentSecret()));
                user.setMspId(mspid);
            }
            sampleOrg.addUser(user); //Remember user belongs to this Org

            final String sampleOrgName = sampleOrg.getName();
            final String sampleOrgDomainName = sampleOrg.getDomainName();

            // src/test/fixture/sdkintegration/e2e-2Orgs/channel/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/

            SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                    Util.findFileSk(Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/",
                            sampleOrgDomainName, format("/users/Admin@%s/msp/keystore", sampleOrgDomainName)).toFile()),
                    Paths.get(testConfig.getTestChannelPath(), "crypto-config/peerOrganizations/", sampleOrgDomainName,
                            format("/users/Admin@%s/msp/signcerts/Admin@%s-cert.pem", sampleOrgDomainName, sampleOrgDomainName)).toFile());

            sampleOrg.setPeerAdmin(peerOrgAdmin); //A special user that can create channels, join peers and install chaincode

        }
    }

    public static void runFabricTest(final SampleStore sampleStore) throws Exception {

        ////////////////////////////
        // Setup client

        //Create instance of client.
        HFClient client = HFClient.createNewInstance();

        client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());

        ////////////////////////////
        //Construct and run the channels
        SampleOrg sampleOrg = testConfig.getIntegrationTestsSampleOrg("peerOrg1");

        Channel fooChannel = constructChannel(FOO_CHANNEL_NAME, client, sampleOrg);

        sampleStore.saveChannel(fooChannel);

        //runChannel(client, fooChannel, true, sampleOrg, 0);

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
        client.setUserContext(sampleOrg.getPeerAdmin());

        Collection<Orderer> orderers = new LinkedList<>();

        for (String orderName : sampleOrg.getOrdererNames()) {

            Properties ordererProperties = testConfig.getOrdererProperties(orderName);

            //example of setting keepAlive to avoid timeouts on inactive http2 connections.
            // Under 5 minutes would require changes to server side to accept faster ping rates.
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});
            ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveWithoutCalls", new Object[] {true});


            orderers.add(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    ordererProperties));
        }

        //Just pick the first orderer in the list to create the channel.

        Orderer anOrderer = orderers.iterator().next();
        orderers.remove(anOrderer);

        ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(TEST_FIXTURES_PATH + "/sdkintegration/e2e-2Orgs/" + TestConfig.FAB_CONFIG_GEN_VERS + "/" + name + ".tx"));

        //Create channel that has only one signer that is this orgs peer admin. If channel creation policy needed more signature they would need to be added too.
        Channel newChannel = client.newChannel(name, anOrderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, sampleOrg.getPeerAdmin()));

        out("Created channel %s", name);

        boolean everyother = true; //test with both cases when doing peer eventing.
        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);

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
        //just for testing ...
        if (doPeerEventing) {
            // Make sure there is one of each type peer at the very least.
            assertFalse(newChannel.getPeers(EnumSet.of(Peer.PeerRole.EVENT_SOURCE)).isEmpty());
            assertFalse(newChannel.getPeers(Peer.PeerRole.NO_EVENT_SOURCE).isEmpty());
        }

        for (Orderer orderer : orderers) { //add remaining orderers if any.
            newChannel.addOrderer(orderer);
        }

        for (String eventHubName : sampleOrg.getEventHubNames()) {

            final Properties eventHubProperties = testConfig.getEventHubProperties(eventHubName);

            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});


            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    eventHubProperties);
            newChannel.addEventHub(eventHub);
        }

        newChannel.initialize();

        out("Finished initialization channel %s", name);

        //Just checks if channel can be serialized and deserialized .. otherwise this is just a waste :)
        byte[] serializedChannelBytes = newChannel.serializeChannel();
        newChannel.shutdown(true);

        return client.deSerializeChannel(serializedChannelBytes).initialize();

    }

    static String getPEMStringFromPrivateKey(PrivateKey privateKey) throws IOException {
        StringWriter pemStrWriter = new StringWriter();
        PEMWriter pemWriter = new PEMWriter(pemStrWriter);

        pemWriter.writeObject(privateKey);

        pemWriter.close();

        return pemStrWriter.toString();
    }
    static void out(String format, Object... args) {

        System.err.flush();
        System.out.flush();

        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();
    }
}
