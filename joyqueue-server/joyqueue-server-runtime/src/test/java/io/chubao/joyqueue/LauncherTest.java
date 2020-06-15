/**
 * Copyright 2019 The JoyQueue Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chubao.joyqueue;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jd.laf.extension.Instantiation;
import com.jd.laf.extension.Name;
import com.jd.laf.extension.Plugin;
import com.jd.laf.extension.SpiLoader;
import io.chubao.joyqueue.broker.Launcher;
import io.chubao.joyqueue.broker.PluginsTest;
import io.chubao.joyqueue.broker.config.Args;
import io.chubao.joyqueue.broker.config.ConfigDef;
import io.chubao.joyqueue.domain.Broker;
import io.chubao.joyqueue.monitor.RestResponse;
import io.chubao.joyqueue.monitor.RestResponseCode;
import io.chubao.joyqueue.nsr.NameService;
import io.chubao.joyqueue.toolkit.URL;
import io.chubao.joyqueue.toolkit.io.Files;
import io.chubao.joyqueue.toolkit.network.IpUtil;
import io.chubao.joyqueue.toolkit.network.http.Get;
import io.chubao.joyqueue.toolkit.time.SystemClock;
import io.chubao.joyqueue.tools.launch.JavaProcessLauncher;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Launch multiple MQ nodes on single machine
 *
 **/
public class LauncherTest {

    private String DEFAULT_CONFIG="joyqueue.properties";
    private String DEFAULT_JOYQUEUE="joyqueue";
    private String ROOT_DIR =System.getProperty("java.io.tmpdir")+ File.separator+DEFAULT_JOYQUEUE;

    public void makeSureDirectoryExist(String path){
        File root=new File(path);
        System.out.println("path:"+path);
        if(!root.exists()){
            root.mkdirs();
        }else{
            System.out.println(String.format("not empty %s:%s", path,Arrays.toString(root.list())));
            Files.deleteDirectory(root);
            if(root.exists()) {
                System.exit(1);
            }else{
                root.mkdirs();
            }
        }
    }

    @Test
    public void launchNameservice(){
//        NameService ns=  PluginsTest.NAMESERVICE.get();
//        NameService nss=  PluginsTest.NAMESERVICEB.get();
//        Assert.assertTrue(ns==nss);
        Collection<Plugin<NameService>> plugins=SpiLoader.INSTANCE.load(NameService.class);
        Plugin<NameService> pl=plugins.iterator().next();
        NameService nameService= Instantiation.ClazzInstance.INSTANCE.newInstance(pl.getName());
        NameService nameServiceB= Instantiation.ClazzInstance.INSTANCE.newInstance(pl.getName());
        Assert.assertTrue(nameService!=nameServiceB);
    }
    @Test
    public void launchOneBroker(){
        String dataDir= ROOT_DIR+File.separator+String.format("second%ssData",File.separator);
        makeSureDirectoryExist(dataDir);
        Broker broker=new Broker();
        broker.setPort(60088);
        long timeout=60;
        TimeUnit unit= TimeUnit.SECONDS;

        String journalKeeperNodes = String.format("%s", IpUtil.getLocalIp()+":"+String.valueOf(broker.getJournalkeeperPort()));
        FutureTask<JavaProcessLauncher> launcher=launchBroker(DEFAULT_CONFIG,broker,dataDir,journalKeeperNodes,timeout,unit);

        BrokerStartState launchSuccess=waitBrokerStart(launcher);
        if(launchSuccess.getLauncher()!=null) {
            launchSuccess.getLauncher().destroy();
        }
        Assert.assertTrue(launchSuccess.state);



    }


    @Test
    public void launchMultiBroker() throws  Exception{


        String dataDir= ROOT_DIR+File.separator+String.format("first%sData",File.separator);
        makeSureDirectoryExist(dataDir);
        Broker firstPort=new Broker();
        firstPort.setPort(40088);
        long timeout=120;
        TimeUnit unit= TimeUnit.SECONDS;


        FutureTask<JavaProcessLauncher> firstBroker=launchBroker(DEFAULT_CONFIG,firstPort,dataDir,null,timeout,unit);
        Thread.sleep(10);


        Broker secondPort=new Broker();
        secondPort.setPort(50088);
        dataDir= ROOT_DIR+File.separator+String.format("second%sData",File.separator);
        makeSureDirectoryExist(dataDir);
        FutureTask<JavaProcessLauncher> secondBroker=launchBroker(DEFAULT_CONFIG,secondPort,dataDir,null,timeout,unit);


        BrokerStartState firstState=waitBrokerStart(firstBroker);
        BrokerStartState secondState=waitBrokerStart(secondBroker);
        if(firstState.getLauncher()!=null) {
            firstState.getLauncher().destroy();
        }
        if(secondState.getLauncher()!=null){
            secondState.getLauncher().destroy();
        }
        boolean allBrokerStart=firstState.state&&secondState.state;
        Assert.assertTrue(allBrokerStart);
    }

    @Ignore
    @Test
    public void launchClusterBroker(){
        makeSureDirectoryExist(ROOT_DIR);
        Broker firstPort=new Broker();
        firstPort.setPort(40088);

        Broker secondPort=new Broker();
        secondPort.setPort(50088);

        Broker thirdPort=new Broker();
        thirdPort.setPort(60088);

        String journalKeeperNodes = String.format("%s,%s,%s",IpUtil.getLocalIp()+":"+String.valueOf(firstPort.getJournalkeeperPort()),
                IpUtil.getLocalIp()+":"+String.valueOf(secondPort.getJournalkeeperPort()),IpUtil.getLocalIp()+":"+String.valueOf(thirdPort.getJournalkeeperPort()));

        String dataDir= ROOT_DIR+File.separator+String.format("first%sData",File.separator);
        makeSureDirectoryExist(dataDir);
        long timeout=60 * 5;
        TimeUnit unit= TimeUnit.SECONDS;

        FutureTask<JavaProcessLauncher> firstBroker=launchBroker(DEFAULT_CONFIG,firstPort,dataDir,journalKeeperNodes,timeout,unit);

        dataDir= ROOT_DIR+File.separator+String.format("second%sData",File.separator);
        makeSureDirectoryExist(dataDir);

        FutureTask<JavaProcessLauncher> secondBroker=launchBroker(DEFAULT_CONFIG,secondPort,dataDir,journalKeeperNodes,timeout,unit);

        dataDir= ROOT_DIR+File.separator+String.format("third%sData",File.separator);
        makeSureDirectoryExist(dataDir);

        FutureTask<JavaProcessLauncher> thirdBroker=launchBroker(DEFAULT_CONFIG,thirdPort,dataDir,journalKeeperNodes,timeout,unit);

        // mock 2 minutes test logic
        // make sure release all process

        BrokerStartState firstState=waitBrokerStart(firstBroker);
        BrokerStartState secondState=waitBrokerStart(secondBroker);
        BrokerStartState thirdState=waitBrokerStart(thirdBroker);
        boolean clusterStartSuccessful=firstState.state&&secondState.state&&thirdState.state;
//        boolean metadata= clusterStartSuccessful&&checkClusterInfo(IpUtil.getLocalIp(),firstPort.getMonitorPort(),timeout,unit,journalKeeperNodes);
        if(firstState.getLauncher()!=null) {
            firstState.getLauncher().destroy();
        }
        if(secondState.getLauncher()!=null){
            secondState.getLauncher().destroy();
        }
        if(thirdState.getLauncher()!=null){
            thirdState.getLauncher().destroy();
        }
        System.out.println("destroy all processes");
        Assert.assertTrue(clusterStartSuccessful);
//        Assert.assertTrue(metadata);

    }

    /**
     * Launch cluster sequence
     *
     **/
    @Test
    public void launchClusterSequence(){
        Broker firstPort=new Broker();
        firstPort.setPort(40088);

        Broker secondPort=new Broker();
        secondPort.setPort(50088);

        Broker thirdPort=new Broker();
        thirdPort.setPort(60088);
//        String expectJournalqKeeperNodes = String.format("%s,%s,%s",IpUtil.getLocalIp()+":"+String.valueOf(firstPort.getJournalkeeperPort()),
//                IpUtil.getLocalIp()+":"+String.valueOf(secondPort.getJournalkeeperPort()),IpUtil.getLocalIp()+":"+String.valueOf(thirdPort.getJournalkeeperPort()));

        String journalKeeperNodes = IpUtil.getLocalIp()+":"+String.valueOf(firstPort.getJournalkeeperPort());
        String dataDir= ROOT_DIR+"/first/Data";
        makeSureDirectoryExist(dataDir);
        long timeout=60 * 7;
        TimeUnit unit= TimeUnit.SECONDS;

        FutureTask<JavaProcessLauncher> firstBroker=launchBroker(DEFAULT_CONFIG,firstPort,dataDir,journalKeeperNodes,timeout,unit);
        BrokerStartState firstState=waitBrokerStart(firstBroker);
        Assert.assertTrue(firstState.state);


        dataDir= ROOT_DIR+"/second/Data";
        makeSureDirectoryExist(dataDir);
        FutureTask<JavaProcessLauncher> secondBroker=launchBroker(DEFAULT_CONFIG,secondPort,dataDir,journalKeeperNodes,timeout,unit);
        BrokerStartState secondState=waitBrokerStart(secondBroker);
        Assert.assertTrue(secondState.state);


        dataDir= ROOT_DIR+"/third/Data";
        makeSureDirectoryExist(dataDir);
        FutureTask<JavaProcessLauncher> thirdBroker=launchBroker(DEFAULT_CONFIG,thirdPort,dataDir,journalKeeperNodes,timeout,unit);
        BrokerStartState thirdState=waitBrokerStart(thirdBroker);
        // mock 2 minutes test logic
        // make sure release all process
        boolean clusterStartSuccessful=firstState.state&&secondState.state&&thirdState.state;
//        boolean metadata= clusterStartSuccessful&&checkClusterInfo(IpUtil.getLocalIp(),firstPort.getMonitorPort(),1,unit,expectJournalqKeeperNodes);
        if(firstState.getLauncher()!=null) {
            firstState.getLauncher().destroy();
        }
        if(secondState.getLauncher()!=null){
            secondState.getLauncher().destroy();
        }
        if(thirdState.getLauncher()!=null){
            thirdState.getLauncher().destroy();
        }
        System.out.println("destroy all processes");
        Assert.assertTrue(clusterStartSuccessful);
//        Assert.assertTrue(metadata);

    }

    /**
     *  Wait broker start success or timeout
     **/
    public BrokerStartState waitBrokerStart( FutureTask<JavaProcessLauncher> broker){

        BrokerStartState startState=new BrokerStartState();
        try {
            startState.setLauncher(broker.get());
            startState.setState(true);
        }catch (Exception e){
            System.out.println(e.getMessage());

        }
        return startState;
    }

    public class BrokerStartState{
        private boolean state=false;
        private JavaProcessLauncher launcher;

        public boolean isState() {
            return state;
        }

        public void setState(boolean state) {
            this.state = state;
        }

        public JavaProcessLauncher getLauncher() {
            return launcher;
        }

        public void setLauncher(JavaProcessLauncher launcher) {
            this.launcher = launcher;
        }
    }


    /**
     * Launcher broker process with config
     *
     **/
    public FutureTask<JavaProcessLauncher> launchBroker(String configFile, Broker broker, String storePath,
                                            String journalKeeperNodes,long timeout,TimeUnit unit){
        Args args=new Args();
        args.append(ConfigDef.APPLICATION_DATA_PATH.key(),storePath);
        args.append(ConfigDef.TRANSPORT_SERVER_PORT.key(),String.valueOf(broker.getPort()));
        if(journalKeeperNodes!=null) {
            args.append(ConfigDef.NAME_SERVER_JOURNAL_KEEPER_NODES.key(), journalKeeperNodes);
            args.append("nameserver.journalkeeper.sql.timeout", String.valueOf(1000 * 60 * 5));
            args.append("nameserver.journalkeeper.waitLeaderTimeout", String.valueOf(1000 * 60 * 5));
        }
        String[] argPairs= args.build();
        String[] finalArgs;
        if(argPairs.length>0){
            finalArgs= new String[argPairs.length+1];
            finalArgs[0]= configFile;
            System.arraycopy(argPairs,0,finalArgs,1,argPairs.length);
        }else{
            finalArgs =new String[1];
            finalArgs[0]= configFile;
        }
        String  localIp= IpUtil.getLocalIp();
        FutureTask<JavaProcessLauncher> futureTask=new FutureTask(()->{
            JavaProcessLauncher launcher = new JavaProcessLauncher(Launcher.class, finalArgs,String.valueOf(broker.getPort()));
            launcher.start();
            try {
                waitBrokerReady(localIp, broker.getMonitorPort(), timeout, unit);
                return launcher;
            }catch (Exception e){
                launcher.destroy();
                throw  e;
            }

        });
        Thread thread=new Thread(futureTask);
        thread.start();
        return futureTask;
    }

    public boolean checkClusterInfo(String host,int port,long timeout,TimeUnit unit,String expectClusterInfo){

        URL  url= URL.valueOf(String.format("http://%s:%s/monitor/metadata/cluster",host,port));
        Get  http= Get.Builder.build().connectionTimeout((int)TimeUnit.SECONDS.toMillis(30))
                .socketTimeout((int)TimeUnit.SECONDS.toMillis(30)).create();
        long timeoutMs= SystemClock.now()+unit.toMillis(timeout);
        do {
            try {
                String resp = http.get(url);
                System.out.println(resp);
                RestResponse<String> clusterMetadata = JSON.parseObject(resp, RestResponse.class);
                JSONObject object = JSON.parseObject(clusterMetadata.getData()).getJSONObject("cluster");
                String voters = parseJournalkeeperNodesFromVoter(object.getString("voters"));
                if (voters != null) {
                    Set<String> expectNodeSet = journalKeeperNodes(expectClusterInfo);
                    Set<String> actualNodeSet = journalKeeperNodes(voters);
                    return actualNodeSet.containsAll(expectNodeSet);
                }
            } catch (IOException ioe) {
                System.err.println(ioe.getMessage());
            }
        }while (SystemClock.now()<timeoutMs);
        return false;
    }

    /**
     * Parse JournalKeeper Nodes
     **/
    public String parseJournalkeeperNodesFromVoter(String voter){
        if(voter==null||voter.length()==0){ return null;}
        List<String> zknodes=JSON.parseArray(voter,String.class);
        StringBuilder builder=new StringBuilder();
        for(String node:zknodes){
            builder.append(node.split("//")[1]).append(",");
        }
        int len= builder.length();
        return builder.toString().substring(0,len-1);
    }

    /**
     * Parse nodes set from String
     **/
    public Set<String> journalKeeperNodes(String nodes){
        String[] ns=nodes.split(",");
        Set<String> nodeSet=new HashSet<>();
        for(String n:ns){
            nodeSet.add(n);
        }
       return nodeSet;
    }

    /**
     *
     * @return true if broker ready
     * @throws TimeoutException
     *
     **/
    public boolean waitBrokerReady(String host,int port,long timeout,TimeUnit unit) throws Exception {
        URL url= URL.valueOf(String.format("http://%s:%s/started",host,port));
        Get  http= Get.Builder.build().connectionTimeout((int)TimeUnit.SECONDS.toMillis(30))
                .socketTimeout((int)TimeUnit.SECONDS.toMillis(10)).create();
        long timeoutMs= SystemClock.now()+unit.toMillis(timeout);
        do{
            try {
                Thread.sleep(1000);
                String startSign = http.get(url);
                RestResponse<Boolean> restResponse=JSON.parseObject(startSign, RestResponse.class);
                if (restResponse != null&& restResponse.getCode()== RestResponseCode.SUCCESS.getCode() &&restResponse.getData()!=null&&restResponse.getData()) {
                    return true;
                }

            }catch (Exception e){
                //System.out.println(e.getMessage());
            }
        }while(timeoutMs>SystemClock.now());
        throw  new TimeoutException("wait for broker ready timeout! ");
    }
}
