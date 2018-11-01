package org.hyperledger.fabric.sdkintegration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpRequest;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KvRwset;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.testutils.TestConfig;
import org.hyperledger.fabric_ca.sdk.HFCAClient;

import javax.json.JsonObject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.lang.String.format;
import static org.hyperledger.fabric.sdk.BlockInfo.EnvelopeType.TRANSACTION_ENVELOPE;
import static org.hyperledger.fabric.sdk.testutils.TestUtils.resetConfig;
import static org.hyperledger.fabric.sdk.testutils.TestUtils.tarBytesToEntryArrayList;
import static org.hyperledger.fabric.sdkintegration.BlockChainToolJavaSDK.out;

@WebServlet(name = "ServletTest",
        urlPatterns = "/test",
        initParams = {
                        @WebInitParam(name = "user",value = "user1"),
                        @WebInitParam(name = "org",value = "org1")
        })
public class ServletTest extends HttpServlet {

    public static org.hyperledger.fabric.sdkintegration.BlockChainToolJavaSDK blockChain = org.hyperledger.fabric.sdkintegration.BlockChainToolJavaSDK.getInstance();

    @Override
    public void init() throws ServletException {
        super.init();
        System.out.println("初始化。。。。。。");
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        doGet(request,response);
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

//        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
//        out.append("<h1>Hello World</h1>");
        response.setContentType("application/json;charset=utf-8");
        response.setCharacterEncoding("UTF-8");
//        String jsonStr ="{\"id\":\"123\",\"name\":\"小黎\"}";
//        out.write(jsonStr);
//        ServletConfig servletConfig =this.getServletConfig();
//        Enumeration<String> params = servletConfig.getInitParameterNames();
//        String paramName = "";
//        while (params.hasMoreElements()){
//             paramName = params.nextElement();
//            out.append(paramName + ":" + servletConfig.getInitParameter(paramName)+"<br>");
//            out(paramName + ":" + servletConfig.getInitParameter(paramName));
//        }
        String index = request.getParameter("index");
        out("方法名:" + index);
        try {
            doService(request,index,out);
        } catch (Exception e) {
            e.printStackTrace();
        }

//        out.close();
    }
    public void doService(HttpServletRequest request,String index, PrintWriter out) throws Exception {

        switch (index){

            case "init":
                initChain(out);
                break;
            case "getChannelInfo":
                getChannelInfo(out);
                break;
            case "getBlockInfo":
                getBlockInfo(out);
                break;
            case "invoke":
                invokeKeyAndValue(request,out);
                break;
            case "query":
                queryValue(request,out);
                break;
            case "chaincode":
                queryChaincode(out);
                break;
            case "querytxdetail":
                queryTxDetail(request,out);
                break;
            default:
                break;
        }
    }

    private void initChain(PrintWriter out) throws Exception {

        Map<String, String> resultMap = new HashMap<>();
        blockChain.checkConfig();
        resultMap = blockChain.setup();
        out.write(JSON.toJSONString(resultMap));
    }

    private void getChannelInfo(PrintWriter out) throws InvalidArgumentException, ProposalException, TransactionException, IOException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InstantiationException, CryptoException, InvocationTargetException {

        JSONObject jsonObject = new JSONObject();
        Channel channel = blockChain.getChannelInfo();
//        jsonObject.put("height",channel.queryBlockchainInfo().getHeight());
        JSONArray peerArray = new JSONArray();
        String[] splitUrl;
        String host,port;
        for (Peer peer: channel.getPeers()){
            JSONObject peerObj = new JSONObject();
            peerObj.put("peerName",peer.getName());
            peerObj.put("peerLocation",peer.getUrl());
            splitUrl = peer.getUrl().split(":");
            if (splitUrl.length > 2){
                host = splitUrl[1].replaceAll("/","");
                port = splitUrl[2];
                try {
                    AdminClient client = new AdminClient(host,Integer.valueOf(port));
                    peerObj.put("statuscode",client.getStatus());
                }catch (Exception e){
                    peerObj.put("statuscode",0);
                }
            }
            peerArray.add(peerObj);
        }
        jsonObject.put("peers",peerArray);
        out.write(jsonObject.toJSONString());
    }

    private void getBlockInfo(PrintWriter out) throws InvalidArgumentException, ProposalException, TransactionException, IOException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InstantiationException, CryptoException, InvocationTargetException {

        JSONObject jsonObject = new JSONObject();
        Channel channel = blockChain.getChannelInfo();
        JSONArray blockArray = new JSONArray();
        int numberOfTx =0;
        long blockNumber = 0;
        String blockData = "";
        String preData = "";
        SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (long current = channel.queryBlockchainInfo().getHeight() - 1; current > -1; --current) {

            JSONObject blockObj = new JSONObject();
            BlockInfo returnedBlock = channel.queryBlockByNumber(current);
            blockNumber = returnedBlock.getBlockNumber();
            blockData = Hex.encodeHexString(returnedBlock.getDataHash());
            numberOfTx = returnedBlock.getTransactionCount();
            preData = Hex.encodeHexString(returnedBlock.getPreviousHash());
            String txId = "";
            String timestamp = "";
            for (BlockInfo.EnvelopeInfo envelopeInfo : returnedBlock.getEnvelopeInfos()){
                if (envelopeInfo.getType() == TRANSACTION_ENVELOPE) {
                    BlockInfo.TransactionEnvelopeInfo transactionEnvelopeInfo = (BlockInfo.TransactionEnvelopeInfo) envelopeInfo;
                    txId = transactionEnvelopeInfo.getTransactionID();
                    timestamp = ft.format(transactionEnvelopeInfo.getTimestamp());
                    blockObj.put("txId",txId);
                    blockObj.put("timestamp",timestamp);
                    blockObj.put("blockNumber",blockNumber);
                    blockObj.put("numberOfTx",numberOfTx);
                    blockObj.put("blockData",blockData);
                    blockObj.put("preData",preData);
                    blockObj.put("org",transactionEnvelopeInfo.getCreator().getMspid());
                    blockArray.add(blockObj);
                }else {
                    txId = envelopeInfo.getTransactionID();
                    timestamp = ft.format(envelopeInfo.getTimestamp());
                    blockObj.put("txId",txId);
                    blockObj.put("timestamp",timestamp);
                    blockObj.put("blockNumber",blockNumber);
                    blockObj.put("numberOfTx",numberOfTx);
                    blockObj.put("blockData",blockData);
                    blockObj.put("preData",preData);
                    blockObj.put("org",envelopeInfo.getCreator().getMspid());
                    blockArray.add(blockObj);
                }
            }
        }
        jsonObject.put("blockInfo",blockArray);
        out.write(jsonObject.toJSONString());
    }
    private void invokeKeyAndValue(HttpServletRequest request,PrintWriter out) throws IOException, NoSuchMethodException, ProposalException, ExecutionException, TimeoutException, InterruptedException, InvalidArgumentException, InstantiationException, InvocationTargetException, IllegalAccessException, CryptoException, ClassNotFoundException {

        Map<String, String> resultMap;
        String revevier = request.getParameter("recevier");
        String mount = request.getParameter("mount");
        String snder = request.getParameter("sender");
        resultMap = blockChain.invoke("move",new String[]{snder,revevier,mount});
        out.write(JSON.toJSONString(resultMap));
    }
    private void queryValue(HttpServletRequest request,PrintWriter out) throws IOException, InstantiationException, ProposalException, NoSuchMethodException, InvalidArgumentException, InvocationTargetException, IllegalAccessException, CryptoException, ClassNotFoundException, TransactionException {

        Map<String, String> resultMapS = new HashMap<>();
        Map<String, String> resultMapR = new HashMap<>();
        String revevier = request.getParameter("recevier");
        String snder = request.getParameter("sender");
        resultMapS = blockChain.query("query",new String[]{snder});
        resultMapR = blockChain.query("query",new String[]{revevier});
        JSONArray jsonArray = new JSONArray();
        jsonArray.add(JSON.toJSON(resultMapS));
        jsonArray.add(JSON.toJSON(resultMapR));
        out.write(jsonArray.toJSONString());
    }

    private void queryChaincode(PrintWriter out) {

        String rerurnStr = blockChain.queryInstalledChaincodes();
        JSONObject jsonObject = new JSONObject();
        if (blockChain.isInteger(rerurnStr)){
            jsonObject.put("code","success");
        }else{
            jsonObject.put("code","error");
        }
        jsonObject.put("data",rerurnStr);
        out.write(jsonObject.toJSONString());
    }

    private void queryTxDetail(HttpServletRequest request,PrintWriter out) throws NoSuchMethodException, InvalidArgumentException, InstantiationException, CryptoException, IllegalAccessException, InvocationTargetException, ClassNotFoundException, ProposalException, InvalidProtocolBufferException, UnsupportedEncodingException {

        String txID = request.getParameter("txID");
        Channel channel = blockChain.getChannelInfo();
        BlockInfo blockInfo = channel.queryBlockByTransactionID(txID);
        JSONObject jsonObject = new JSONObject();
        SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currTxId = "";
        for (BlockInfo.EnvelopeInfo envelopeInfo : blockInfo.getEnvelopeInfos()){
            currTxId = envelopeInfo.getTransactionID()==null?"":envelopeInfo.getTransactionID();
            if (currTxId.equals(txID)){
                jsonObject.put("transationsID",currTxId);
                jsonObject.put("creator",envelopeInfo.getCreator().getMspid());
                jsonObject.put("channelName",envelopeInfo.getChannelId());
                jsonObject.put("timstamp",ft.format(envelopeInfo.getTimestamp()));
                jsonObject.put("type",envelopeInfo.getType());
                if (envelopeInfo.getType() == TRANSACTION_ENVELOPE) {
                     BlockInfo.TransactionEnvelopeInfo transactionEnvelopeInfo = (BlockInfo.TransactionEnvelopeInfo) envelopeInfo;
                    for (BlockInfo.TransactionEnvelopeInfo.TransactionActionInfo transactionActionInfo : transactionEnvelopeInfo.getTransactionActionInfos()) {
                        String endorser="";
                        for (int n = 0; n < transactionActionInfo.getEndorsementsCount(); ++n) {
                            BlockInfo.EndorserInfo endorserInfo = transactionActionInfo.getEndorsementInfo(n);
                            if(n>0) endorser+=",";
                            endorser += endorserInfo.getMspid();
                        }
                        jsonObject.put("endorser",endorser);
                        TxReadWriteSetInfo rwsetInfo = transactionActionInfo.getTxReadWriteSet();
                        if (null != rwsetInfo) {
                            String readDatas = "";
                            String writeData = "";
                            for (TxReadWriteSetInfo.NsRwsetInfo nsRwsetInfo : rwsetInfo.getNsRwsetInfos()) {
                                final String namespace = nsRwsetInfo.getNamespace();
                                jsonObject.put("chainCode",namespace);
                                KvRwset.KVRWSet rws = nsRwsetInfo.getRwset();
                                readDatas = namespace + "\n";
                                for (KvRwset.KVRead readList : rws.getReadsList()) {
                                    readDatas += readList.getKey() + "\n";
                                    readDatas += readList.getVersion() + "\n";
                                }
                                writeData += namespace + "\n";
                                for (KvRwset.KVWrite writeList : rws.getWritesList()) {
                                    writeData += URLDecoder.decode(writeList.getKey().toString(),"UTF-8")+"\n";
                                    writeData += URLDecoder.decode(writeList.getValue().toString(),"UTF-8")+"\n";
                                }
                            }
                            jsonObject.put("reads",readDatas);
                            jsonObject.put("writes",writeData);

                        }
                    }
                     jsonObject.put("content","");
                 }
            }
        }
        out.write(jsonObject.toJSONString());
    }
    private void queryPeerInfo(PrintWriter out){


    }
}

