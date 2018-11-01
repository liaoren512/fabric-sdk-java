package org.hyperledger;

import java.io.File;
import java.nio.file.Paths;

public class Test {
    public static void main(String[] args) {
//        File file = new File("D:/");
//        String[] files = file.list();
//        for (String f : files){
//            System.out.println(f);
//        }
//        System.out.println("------------------");
//        File[] fs = file.listFiles();
//        for (File f : fs){
//            System.out.println(f);
//        }
//        System.out.println(System.getProperty("os.name"));
//        System.out.println(System.getProperty("os.arch"));
        //D:/uepstudio/workspace_idea/BlockTest/src/test/fixture/sdkintegration/e2e-2Orgs/v1.1/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/
        File[] aaa = Paths.get("src/test/fixture/sdkintegration/e2e-2Orgs/v1.1/crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore/").toFile().listFiles();
        System.out.println(aaa.length);
    }
}
