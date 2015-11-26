package org.openbaton.monitoring.agent;


import com.google.gson.*;
import com.mashape.unirest.http.HttpMethod;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.openbaton.catalogue.nfvo.EndpointType;
import org.openbaton.catalogue.nfvo.Item;
import org.openbaton.catalogue.util.IdGenerator;
import org.openbaton.monitoring.agent.alarm.catalogue.*;
import org.openbaton.monitoring.agent.exceptions.MonitoringException;
import org.openbaton.monitoring.agent.performance.management.catalogue.*;
import org.openbaton.monitoring.agent.zabbix.api.ZabbixApiManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.rmi.RemoteException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.primitives.Doubles.tryParse;

/**
 * Created by mob on 22.10.15.
 */
public class ZabbixMonitoringAgent extends AmpqMonitoringPlugin {

    private int historyLength;
    private int requestFrequency;
    private ZabbixSender zabbixSender;
    private ZabbixApiManager zabbixApiManager;
    private String notificationReceiverServerContext;
    private int notificationReceiverServerPort;
    private Gson mapper;
    private Random random=new Random();
    private Logger log = LoggerFactory.getLogger(this.getClass());
    private List<AlarmEndpoint> subscriptions;
    private Map<String,PmJob> pmJobs;
    private Map<String,Threshold> thresholds;
    private String type;
    private Map<String, List<String>> triggerIdHostnames;
    private Map<String, String> triggerIdActionIdMap;
    private LimitedQueue<State> history;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    //Server properties
    private HttpServer server;
    private MyHandler myHandler;
    //
    private Runnable updateHistory = new Runnable() {
        @Override
        public void run() {
            JsonObject responseObj = null;
            String params = "{'output': ['name'], 'selectItems': ['key_', 'lastvalue']}";
            try {
                responseObj = zabbixSender.callPost(params, "host.get");
            } catch (MonitoringException e) {
                log.error("Exception while updating hosts:" + e.getMessage());
            }
            long timestamp = System.currentTimeMillis()/1000;
            JsonArray hostArray = responseObj.get("result").getAsJsonArray();
            Iterator<JsonElement> iterator = hostArray.iterator();


            // String is the hostname
            Map<String, HistoryObject> snapshot = new HashMap<String, HistoryObject>();
            while (iterator.hasNext()) {
                JsonObject jsonObj = iterator.next().getAsJsonObject();
                String hostName = jsonObj.getAsJsonObject().get("name").getAsString();
                JsonArray itemArray = jsonObj.get("items").getAsJsonArray();
                Iterator<JsonElement> itemIterator = itemArray.iterator();
                HistoryObject ho = new HistoryObject();
                ho.setHostId(jsonObj.get("hostid").getAsString());
                // add the values to the HistoryObject
                while (itemIterator.hasNext()) {
                    JsonObject item = itemIterator.next().getAsJsonObject();
                    ho.setMeasurement(item.get("key_").getAsString(), item.get("lastvalue").getAsString());
                }

                snapshot.put(hostName, ho);
            }
            State state = new State(timestamp, snapshot);
            synchronized (history) {
                history.add(state);
            }
        }
    };


    public ZabbixMonitoringAgent() throws RemoteException {
        super();
        init();
        try {
            launchServer(notificationReceiverServerPort,notificationReceiverServerContext);
        } catch (IOException e) {
            throw new RemoteException(e.getMessage(),e);
        }
    }

    /**
     * @param hostnames a list of hostnames
     * @param metrics a list of the metrics that shall be retrieved from every hostname
     * @param period you get for every suitable metric the average value it had for the last <period> seconds of time
     * @return a list of Items
     * @throws RemoteException
     *
     * The method gives back a list of Items.
     * For every combination of hostname and metric, one ZabbixItem will be in the list.
     * Every ZabbixItem contains the hostname, the host id, the latest value of one metric
     * and the average value of the metric in the last <period> seconds.
     * The average value is in the field 'value', the latest value is in the field 'lastValue'.
     * The ZabbixItem's id is always null and the version 0.
     *
     */
    //@Override
    public List<Item> getMeasurementResults(List<String> hostnames, List<String> metrics, String period) throws RemoteException {

        List<Item> items = new LinkedList<>();
        long currTime = System.currentTimeMillis()/1000;
        long startingTime = currTime - Long.parseLong(period); // that's the point of time where requested history starts

        synchronized (history) {
            LinkedList<State> historyImportant = new LinkedList<>();  // the part of the history which lies in the period
            Iterator<State> iterator = history.descendingIterator();

            // get the latest history entry
            if (iterator.hasNext()) {
                State entry = iterator.next();
                historyImportant.add(entry);
                // check if the period is too long
                if (!iterator.hasNext() && entry.getTime() > startingTime)
                    throw new RemoteException("The period is too long for the existing history.");
            }

            while (iterator.hasNext()) {
                State entry = iterator.next();
                if (entry.getTime() < startingTime) {
                    break;
                }
                historyImportant.add(entry);
                if (!iterator.hasNext() && entry.getTime() > startingTime)
                    throw new RemoteException("The period is too long for the existing history.");
            }

            for (String host : hostnames) {
                // check if the host exists in the latest state of history
                if (!historyImportant.peekFirst().getHostsHistory().containsKey(host))
                    throw new RemoteException("The hostname " + host + " does not exist.");

                for (String metric : metrics) {
                    Iterator<State> historyIterator = historyImportant.iterator();

                    Item item = new Item();
                    item.setHostname(host);
                    item.setMetric(metric);

                    HistoryObject hObj = historyIterator.next().getHostsHistory().get(host);
                    if (!hObj.keyExists(metric))
                        throw new RemoteException("The metric " + metric + " does not exist for host " + host + ".");

                    item.setHostId(hObj.getHostId());

                    String value = hObj.getMeasurement(metric);
                    item.setLastValue(value);

                    Double avg = (Double) tryParse(value);
                    if (avg != null) { // it is a number and no string
                        while (historyIterator.hasNext()) {
                            Map<String, HistoryObject> hostsAndHistory = historyIterator.next().getHostsHistory();
                            if (!hostsAndHistory.containsKey(host)) {
                                throw new RemoteException("The period is too long for host " + host +
                                        " because he just started existing inside the specified time frame.");
                            }
                            if (!hostsAndHistory.get(host).keyExists(metric)) {
                                throw new RemoteException("The metric " + metric +
                                        "did not exist at every time in the specified period for host " + host + ".");
                            }
                            avg += Double.parseDouble(hostsAndHistory.get(host).getMeasurement(metric));
                        }
                        avg /= historyImportant.size();
                        value = avg.toString();
                    }
                    // if the metric's value is a String, just store the last value as value
                    item.setValue(value);

                    items.add(item);
                }
            }
        }
        return items;
    }

    private void init() throws RemoteException {
        loadProperties();
        String zabbixIp = properties.getProperty("zabbix-ip");
        String zabbixPort = properties.getProperty("zabbix-port");
        String username = properties.getProperty("user");
        String password = properties.getProperty("password");
        zabbixSender = new ZabbixSender(zabbixIp,zabbixPort,username,password);
        zabbixApiManager= new ZabbixApiManager(zabbixSender);
        String nrsp=properties.getProperty("notification-receiver-server-port","8010");
        notificationReceiverServerPort=Integer.parseInt(nrsp);

        notificationReceiverServerContext = properties.getProperty("notification-receiver-server-context","/zabbixplugin/notifications");

        type = properties.getProperty("type");
        requestFrequency = Integer.parseInt(properties.getProperty("client-request-frequency"));
        historyLength = Integer.parseInt(properties.getProperty("history-length"));
        history = new LimitedQueue<>(historyLength);
        mapper = new GsonBuilder().setPrettyPrinting().create();
        subscriptions=new ArrayList<>();
        subscriptions.add(new AlarmEndpoint("fmsystem",null, EndpointType.REST,"http://localhost:9000/alarm/vr",PerceivedSeverity.MAJOR));
        triggerIdHostnames=new HashMap<>();
        pmJobs=new HashMap<>();
        thresholds=new HashMap<>();
        triggerIdActionIdMap=new HashMap<>();
        try {
            zabbixSender.authenticate();
        } catch (MonitoringException e) {
            log.error("Authentication failed: " + e.getMessage());
            throw new RemoteException("Authentication to Zabbix server failed.");
        }

        updateHistory.run();
        scheduler.scheduleAtFixedRate(updateHistory, requestFrequency, requestFrequency, TimeUnit.SECONDS);
    }
    /**
     * terminate the scheduler safely
     */
    public void terminate() {
        shutdownAndAwaitTermination(scheduler);
        server.stop(10);
    }
    void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    private void handleNotification(ZabbixNotification zabbixNotification) {
        List<AlarmEndpoint> subscribers = getSubscribers(zabbixNotification);

        if(subscribers.isEmpty())
            return;



        //Check if the trigger is crossed for the first time
        if(isNewNotification(zabbixNotification)) {
            //TODO create Threshold crossed notification
            log.debug("Yes is new");
            //if the severity of the notification is more than Not classified or Information,
            // create an alarm or change the state of an existing alarm
            if (!(zabbixNotification.getTriggerSeverity().equals("Not classified") || zabbixNotification.getTriggerSeverity().equals("Information"))) {
                log.debug("creating alarm");
                Alarm alarm = createAlarm(zabbixNotification);
                AbstractVirtualizedResourceAlarm notification = new VirtualizedResourceAlarmNotification(alarm.getTriggerId(), alarm);
                log.debug("Sending alarm:" + alarm);

                sendFaultNotification(notification,subscribers);
            }
            List<String> hostnames= new ArrayList<>();
            hostnames.add(zabbixNotification.getHostName());
            triggerIdHostnames.put(zabbixNotification.getTriggerId(),hostnames);
            log.debug("triggerIdHostnames: "+triggerIdHostnames);
        }
        else {
            //TODO create Threshold crossed notification
            if (!(zabbixNotification.getTriggerSeverity().equals("Not classified") || zabbixNotification.getTriggerSeverity().equals("Information"))) {

                AlarmState alarmState= zabbixNotification.getTriggerStatus() == TriggerStatus.OK ? AlarmState.CLEARED : AlarmState.UPDATED;
                AbstractVirtualizedResourceAlarm notification = new VirtualizedResourceAlarmStateChangedNotification(zabbixNotification.getTriggerId(),alarmState);

                sendFaultNotification(notification,subscribers);
            }
        }
    }

    private boolean isNewNotification(ZabbixNotification zabbixNotification){
        if(triggerIdHostnames.get(zabbixNotification.getTriggerId())==null)
            return true;
        if(!(triggerIdHostnames.get(zabbixNotification.getTriggerId()).contains(zabbixNotification.getHostName())))
            return true;
        return false;
    }


    private void sendFaultNotification(AbstractVirtualizedResourceAlarm notification,List<AlarmEndpoint> subscribers) {

        for(AlarmEndpoint ae: subscribers){
            try {
                notifyFault(ae, notification);
            } catch (UnirestException e) {
                log.error(e.getMessage(),e);
            }
        }

    }

    private List<AlarmEndpoint> getSubscribers(ZabbixNotification notification) {

        List<AlarmEndpoint> subscribersForNotification = new ArrayList<>();

        PerceivedSeverity notificationPerceivedSeverity = getPerceivedSeverity(notification.getTriggerSeverity());
        for(AlarmEndpoint ae : subscriptions){
            if(notificationPerceivedSeverity.ordinal()>=ae.getPerceivedSeverity().ordinal()){
                subscribersForNotification.add(ae);
            }
        }
        return subscribersForNotification;
    }

    private Alarm createAlarm(ZabbixNotification zabbixNotification) {
        Alarm alarm= new Alarm();
        alarm.setTriggerId(zabbixNotification.getTriggerId());

        //AlarmRaisedTime: It indicates the date and time when the alarm is first raised by the managed object. 
        alarm.setAlarmRaisedTime(zabbixNotification.getEventDate()+" "+zabbixNotification.getEventTime());
        //EventTime: Time when the fault was observed. 
        DateFormat dateFormat= new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        alarm.setEventTime(dateFormat.format(date));

        //AlarmState: State of the alarm, e.g. “fired”, “updated”, “cleared”. 
        alarm.setAlarmState(AlarmState.FIRED);
        /*
        Type of the fault.The allowed values for the faultyType attribute depend
        on the type of the related managed object. For example, a resource of type “compute”
        may have faults of type “CPU failure”, “memory failure”, “network card failure”, etc. 
        */
        alarm.setFaultType(getFaultType(zabbixNotification.getItemKey()));
        /*
        Identifier of the affected managed Object. The Managed Objects for this information element
        will be virtualised resources. These resources shall be known by the Virtualised Resource Management interface. 
        */
        //TODO handle a notification fired by a threshold on more than one hostname
        alarm.setResourceId(zabbixNotification.getHostName());

        //Perceived severity of the managed object failure
        alarm.setPerceivedSeverity(getPerceivedSeverity(zabbixNotification.getTriggerSeverity()));

        return alarm;
    }

    private PerceivedSeverity getPerceivedSeverity(String triggerSeverity) {
        switch (triggerSeverity){
            case "Not classified": return PerceivedSeverity.INDETERMINATE;
            case "Information": return PerceivedSeverity.WARNING;
            case "Warning": return PerceivedSeverity.WARNING;
            case "Average": return PerceivedSeverity.MINOR;
            case "High":  return PerceivedSeverity.MAJOR;
            case "Disaster": return PerceivedSeverity.CRITICAL;
        }
        return null;
    }
    private Integer getPriority(PerceivedSeverity perceivedSeverity) {
        switch (perceivedSeverity){
            case INDETERMINATE: return 0/*Not classified*/;
            case WARNING: return 2/*"Warning"*/;
            case MINOR: return 3/*"Average"*/;
            case MAJOR:  return 4/*"High"*/;
            case CRITICAL: return 5/*"Disaster"*/;
        }
        return null;
    }

    private FaultType getFaultType(String itemKey) {
        Metric metric;
        int index= itemKey.indexOf('[');
        if(index!=-1)
            metric=getMetric(itemKey.substring(0, index));
        else metric=getMetric(itemKey);


        switch (metric){
            case SYSTEM_CPU_LOAD: return FaultType.COMPUTE_HOGS_CPU;
            case NET_TCP_LISTEN: return FaultType.IO_NETWORK_FRAME_TRANSMIT;
        }
        return null;
    }

    private Metric getMetric(String substring) {
        String metricString= substring.toUpperCase().replaceAll("\\.","_");
        Metric result=Metric.valueOf(metricString);
        log.debug("Obtained Metric: "+result+" from: "+substring);
        return result;
    }


    private void launchServer(int port, String context) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 1);
        myHandler=new MyHandler();
        server.createContext(context, myHandler);
        log.debug("Notification receiver server running on url: "+server.getAddress()+" port:"+server.getAddress().getPort());
        server.setExecutor(null);
        server.start();
    }

    @Override
    public String subscribe(AlarmEndpoint endpoint) {
        String subscriptionId=IdGenerator.createId();
        endpoint.setId(subscriptionId);
        subscriptions.add(endpoint);
        return subscriptionId;
    }

    @Override
    public void unsubscribe(String alarmEndpointId) {
        Iterator<AlarmEndpoint> iterator = subscriptions.iterator();
        while (iterator.hasNext()){
            AlarmEndpoint currentAlarmEndpoint = iterator.next();
            if(currentAlarmEndpoint.getId().equals(alarmEndpointId)){
                iterator.remove();
                break;
            }
        }
    }

    @Override
    public void notifyFault(AlarmEndpoint endpoint, AbstractVirtualizedResourceAlarm event) throws UnirestException {
        HttpResponse<String> response = null;
        if(event instanceof VirtualizedResourceAlarmNotification){
            VirtualizedResourceAlarmNotification vran = (VirtualizedResourceAlarmNotification) event;
            String jsonAlarm = mapper.toJson(vran,VirtualizedResourceAlarmNotification.class);
            log.debug("Sending VirtualizedResourceAlarmNotification: "+jsonAlarm +" to: "+endpoint.getEndpoint());
            response=zabbixSender.doRestCallWithJson(endpoint.getEndpoint(),jsonAlarm, HttpMethod.POST,"application/json");
        }
        else if (event instanceof VirtualizedResourceAlarmStateChangedNotification){
            VirtualizedResourceAlarmStateChangedNotification vrascn= (VirtualizedResourceAlarmStateChangedNotification) event;
            String jsonAlarm = mapper.toJson(vrascn,VirtualizedResourceAlarmStateChangedNotification.class);
            log.debug("Sending VirtualizedResourceAlarmStateChangedNotification: "+jsonAlarm+" to: "+endpoint);
            response=zabbixSender.doRestCallWithJson(endpoint.getEndpoint(),jsonAlarm, HttpMethod.PUT,"application/json");
        }
        log.debug("Response is:"+response.getBody());
    }

    @Override
    public List<Alarm> getAlarmList(String vnfId, PerceivedSeverity perceivedSeverity) {
        return null;
    }

    @Override
    public String createPMJob(ObjectSelection objectSelection, List<String> performanceMetrics, List<String> performanceMetricGroup, Integer collectionPeriod,
                                    Integer reportingPeriod) throws MonitoringException {
        if(objectSelection ==null || objectSelection.getObjectInstanceIds().isEmpty())
            throw new MonitoringException("The objectSelection is null or empty");
        if( performanceMetrics==null && performanceMetricGroup==null)
            throw new MonitoringException("At least performanceMetrics or performanceMetricGroup need to be present");
        if(collectionPeriod<0 || reportingPeriod<0)
            throw new MonitoringException("The collectionPeriod or reportingPeriod is negative");

        PmJob pmJob=null;
        if(performanceMetrics!=null) {
            if(performanceMetrics.isEmpty())
                throw new MonitoringException("PerformanceMetrics is null");
            pmJob = new PmJob(objectSelection, collectionPeriod);

            for(String hostname : objectSelection.getObjectInstanceIds()) {
                String hostId = zabbixApiManager.getHostId(hostname);
                String interfaceId = zabbixApiManager.getHostInterfaceId(hostId);
                for (String performanceMetric : performanceMetrics) {
                    String itemId = zabbixApiManager.createItem("ZabbixItem on demand: " + performanceMetric, collectionPeriod, hostId, 0, 3, performanceMetric, interfaceId);
                    pmJob.addPerformanceMetric(itemId, performanceMetric);
                }
            }
        }
        pmJobs.put(pmJob.getPmjobId(),pmJob);
        return pmJob.getPmjobId();
    }

    @Override
    public List<String> deletePMJob(List<String> pmJobIdsToDelete) throws MonitoringException {
        if(pmJobIdsToDelete == null)
            throw new MonitoringException("The list of pmJob ids is null");
        if(pmJobIdsToDelete.isEmpty())
            throw new MonitoringException("The list of pmJob is empty");
        List<String> deletedPmJobId=new ArrayList<>();
        for(String pmJobId : pmJobIdsToDelete){
            PmJob pmJob = pmJobs.get(pmJobId);
            if(pmJob!=null){
                List<String> itemIds= new ArrayList<>();
                itemIds.addAll(pmJob.getPerformanceMentricIds());
                zabbixApiManager.deleteItems(itemIds);
                deletedPmJobId.add(pmJobId);
            }

        }
        return deletedPmJobId;
    }

    @Override
    public List<Item> queryPMJob(List<String> hostnames, List<String> metrics, String period) throws RemoteException {
       return getMeasurementResults(hostnames,metrics,period);
    }

    @Override
    public void subscribe() {

    }

    @Override
    public void notifyInfo() {

    }

    @Override
    public String createThreshold(List<ObjectSelection> objectSelectors, String performanceMetric, ThresholdType thresholdType, ThresholdDetails thresholdDetails) throws MonitoringException {

        if(objectSelectors ==null || objectSelectors.isEmpty())
            throw new MonitoringException("The objectSelectors is null or empty");
        if( (performanceMetric==null && performanceMetric.isEmpty()))
            throw new MonitoringException("The performanceMetric needs to be present");
        if(thresholdDetails==null)
            throw new MonitoringException("The thresholdDetails is null");
        //TODO investigate which are the cases where we need more than one objectSelector
        //Now we use only one objectSelector


        List<String> hostnames= objectSelectors.get(0).getObjectInstanceIds();


        String firstHostname= hostnames.get(0);
        String thresholdExpression="";
        for(String hostname: hostnames){
            String singleHostExpression="";
            if(!hostname.equals(firstHostname))
                singleHostExpression+="&";
            singleHostExpression+="{"+hostname+":"+performanceMetric;
            if(thresholdDetails.getFunction() != null && !thresholdDetails.getFunction().isEmpty())
                singleHostExpression+="."+thresholdDetails.getFunction();
            singleHostExpression+="}"+thresholdDetails.getTriggerOperator()+thresholdDetails.getValue();
            thresholdExpression+=singleHostExpression;
        }
        log.debug("Threshold expression: "+thresholdExpression);
        String thresholdName =  "Threshold on demand "+random.nextInt(100);
        String triggerId = zabbixApiManager.createTrigger(thresholdName,thresholdExpression,getPriority(thresholdDetails.getPerceivedSeverity()));

        Threshold threshold = new Threshold(objectSelectors,performanceMetric,thresholdType,thresholdDetails);
        threshold.setThresholdId(triggerId);

        thresholds.put(threshold.getThresholdId(),threshold);

        //create an action to be notified when this threshold is crossed
        String actionId = zabbixApiManager.createAction("Action for (" + thresholdName + ")", triggerId);
        triggerIdActionIdMap.put(triggerId,actionId);

        return threshold.getThresholdId();
    }

    @Override
    public List<String> deleteThreshold(List<String> thresholdIds) throws MonitoringException {
        List<String> triggerIdDeleted = zabbixApiManager.deleteTriggers(thresholdIds);
        if(!triggerIdDeleted.containsAll(thresholdIds)){
            log.warn("Triggers deleted: "+triggerIdDeleted+" are less than the triggers to delete: "+thresholdIds);
        }
        //Here we are going to delete the actions linked with the triggers effectively DELETED
        List<String> actionIdsToDelete = getActions(triggerIdDeleted);
        List<String> actionIdDeleted = zabbixApiManager.deleteActions(actionIdsToDelete);
        if(!actionIdsToDelete.containsAll(actionIdDeleted)){
            log.warn("Actions deleted: "+actionIdDeleted+" are less than the actions to delete: "+actionIdsToDelete);
        }

        //Update the local state
        for(String triggerId : triggerIdDeleted){
            thresholds.remove(triggerId);
            String actionId=triggerIdActionIdMap.get(triggerId);
            if(!actionIdDeleted.contains(actionId))
                log.warn("The action with id:"+actionId+" referred to the trigger id: "+triggerId+" needs to be removed manually on zabbix");
            triggerIdActionIdMap.remove(triggerId);
        }

        return triggerIdDeleted;
    }

    private List<String> getActions(List<String> triggerIdDeleted) {
        List<String> result = new ArrayList<>();
        for(String triggerId : triggerIdDeleted){
            String actionId = triggerIdActionIdMap.get(triggerId);
            if(actionId!=null){
                result.add(actionId);
            }
        }
        return result;
    }

    @Override
    public void queryThreshold(String queryFilter) {

    }

    class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) {
            InputStream is = t.getRequestBody();

            String message = read(is);
            checkRequest(message);
            String response = "";
            try {
                t.sendResponseHeaders(200, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }catch (IOException e){
                log.error(e.getMessage(),e);
            }
        }

        private void checkRequest(String message) {
            log.debug("\n\n");
            log.debug("Received: "+message);
            ZabbixNotification zabbixNotification;
            try {
                zabbixNotification = mapper.fromJson(message, ZabbixNotification.class);
            }catch (Exception e){
                log.warn("Impossible to retrive the ZabbixNotification received",e);
                return;
            }
            log.debug("\n");
            log.debug("ZabbixNotification: "+zabbixNotification);
            handleNotification(zabbixNotification);
        }
        private String read(InputStream is){

            StringBuilder responseStrBuilder = new StringBuilder();

            String inputStr;

            try (BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                while ((inputStr = streamReader.readLine()) != null)
                    responseStrBuilder.append(inputStr);
            } catch (IOException e) {
               log.error(e.getMessage(),e);
            }
            return responseStrBuilder.toString();
        }


    }

}
