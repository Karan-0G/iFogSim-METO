
package org.fog.test.perfeval;

import org.cloudbus.cloudsim.*;
//Imports core CloudSim classes like Datacenter, Vm, Cloudlet, DatacenterBroker, etc., which model cloud resources and virtual machines.
import org.cloudbus.cloudsim.core.CloudSim;
//Entry point to initialize the CloudSim simulation environment. Manages the event-driven simulation loop.
import org.cloudbus.cloudsim.power.PowerHost;
//Represents a host machine that models power consumption (used for energy-aware simulations).
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
//Allocates bandwidth (BW) to VMs in a simple manner (non-prioritized, fixed allocation).
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
//Provides a simple policy to allocate processing elements (PEs) to VMs.
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
//Allocates RAM to VMs using a basic policy (no overcommit, strict limits).
import org.fog.application.Application;
//Represents a complete fog application, composed of modules and data flows.
import org.fog.application.AppEdge;
//Defines edges in the application DAG (data/control flow between modules).
import org.fog.application.AppLoop;
//Defines control loops, which are sequences of modules representing an end-to-end path (e.g., sensor → edge → actuator).
import org.fog.application.selectivity.FractionalSelectivity;
//Models the selectivity of data transfer between modules (e.g., data reduction: how many outputs per input).
import org.fog.entities.*;
//Imports all core fog entities like:
//FogDevice: fog nodes or gateways
//Sensor: sends data to modules
//Actuator: receives processed data
//FogBroker: acts as a controller between app and fog
import org.fog.policy.AppModuleAllocationPolicy;
//Specifies the policy used for allocating application modules to fog devices.
import org.fog.scheduler.StreamOperatorScheduler;
//Scheduler for stream-processing modules running on fog devices (decides order/timing of execution).
import org.fog.placement.Controller;
//Central orchestrator in iFogSim simulation.
//simulation initialization,
//managing devices, applications, and module placement,
//deploying application logic.
import org.fog.utils.FogLinearPowerModel;
//A linear model to compute the power usage of fog devices based on CPU utilization.
import org.fog.utils.FogUtils;
//Contains utility functions like bandwidth conversions, random distributions, delay calculations, etc.
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

//Tracks application execution time and logs latency statistics for performance evaluation.
import java.util.*;//Standard Java utilities

public class METO {
    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();
    static List<Task> taskList = new ArrayList<>();
    static List<FogNodeWrapper> fnList = new ArrayList<>();
    static Map<Integer, Double> energyMap = new HashMap<>();
    static String appId ="METO_App";


    public static void main(String[] args) {
        Log.printLine("Starting METO Simulation...");
        try {
            CloudSim.init(1, Calendar.getInstance(), false);//Initializes CloudSim, which is required before using iFogSim.
            FogBroker broker = new FogBroker("broker");
            //Creates a FogBroker which manages application modules and resource requests on behalf of the use

            createFogDevices(broker.getId());//Creates all fog devices (FN, cloud, gateways, etc.).
            createTasks();// Initializes the list of application tasks (to be offloaded).
            buildFogNodeWrappers();// Wraps FogDevices into custom wrappers (FogNodeWrapper) for additional properties like power and quota.

            Map<Task, double[][]> taskMatrices = DecisionMatrixUtil.constructTaskMatrices(taskList, fnList);
            Map<FogNodeWrapper, double[][]> fnMatrices = DecisionMatrixUtil.constructFogNodeMatrices(taskList, fnList);
            //Constructs decision matrices for tasks and FNs.
            //These are used for CRITIC weight calculation and TOPSIS ranking.
            
            Map<Integer, List<Integer>> taskPreferences = new HashMap<>(); //Task → ranked list of preferred FNs.
            Map<Integer, List<Integer>> fnPreferences = new HashMap<>();//ranked list of preferred tasks.
            Map<Integer, Integer> fnQuotas = new HashMap<>();//FN → how many tasks it can handle.

            int taskId = 0;
            for (Task task : taskList) {
                double[][] matrix = taskMatrices.get(task);
                double[] weights = DecisionMatrixUtil.computeCRITICWeights(matrix);
                taskPreferences.put(taskId++, DecisionMatrixUtil.applyTOPSIS(matrix, weights));
            }
            //For each task:
            //Gets the matrix of values (criteria values per FN).
            //Calculates weights using the CRITIC method.
            //Applies TOPSIS to rank FNs.
            //Stores the ranked list in taskPreferences.

            int fnId = 0;
            for (FogNodeWrapper fn : fnList) {
                double[][] matrix = fnMatrices.get(fn);
                double[] weights = DecisionMatrixUtil.computeCRITICWeights(matrix);
                fnPreferences.put(fnId, DecisionMatrixUtil.applyTOPSIS(matrix, weights));
                fnQuotas.put(fnId, fn.quota);
                fnId++;
            }
            //Similarly, for each FN:
            //Gets matrix of task criteria.
            //Calculates weights and applies TOPSIS to rank tasks.
            //Saves ranked list in fnPreferences.
            //Sets its task quota in fnQuotas

            Map<Integer, Integer> matches = METOMatcher.runMatching(taskPreferences, fnPreferences, fnQuotas);
            //Runs the Deferred Acceptance Algorithm (DAA).
            //Matches tasks to FNs based on mutual preferences and quotas.
            //Output: map from taskId → fnId.

            Log.printLine("\nFinal Task-FN Assignment:");
            
            for (Map.Entry<Integer, Integer> entry : matches.entrySet()) {
                int tId = entry.getKey();
                int fId = entry.getValue();
                Task task = taskList.get(tId);
                FogNodeWrapper fn = fnList.get(fId);
                //Iterates through all matched pairs: task ↔ FN.
                
                double txTime = (double) task.inputSize / 10e6;//Transmission time: based on input size and 10 Mbps bandwidth.
                double compTime = (double) task.cycles / fn.capacity;//Computation time: number of CPU cycles / FN capacity.
                double rxTime = (double) task.outputSize / 10e6;//Reception time: based on output size.
                double energy = fn.txPower * (txTime + rxTime) + fn.powerBusy * compTime;//Estimates total energy consumed by FN to handle the task.
                energyMap.put(tId, energy);//Stores it in energyMap

                Log.printLine("Task T" + tId + " -> FN F" + fId +
                        " | Power: " + String.format("%.3f", fn.powerBusy) +
                        " W | Energy = " + String.format("%.4f", energy) + " J");
                //Prints each assignment with energy and power consumption details.
            }

            Application application = createApplication("METO_App", broker.getId());
            //Creates the application with modules and data flows.
           
            Controller controller = new Controller("master-controller", fogDevices, sensors, actuators);
            //Instantiates the Controller, which manages simulation entities.
            controller.submitApplication(
                application,
                new ModulePlacementMETO(fogDevices, application, taskList, fnList, matches)
            );
            //Submits the application with a custom module placement policy (ModulePlacementMETO), based on the METO matches.
            


            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
            //Sets the simulation start time for logging and metrics.
            CloudSim.startSimulation();
            CloudSim.stopSimulation();//Starts and runs the simulation.
            Log.printLine("METO Simulation finished!");
          


        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Error in METO Simulation!");
            
            //Catches and prints any error that happens during simulation setup or execution.
        }
        
    }

    private static void createTasks() {//This method generates a list of computational tasks with randomized parameters.
    	 taskList.clear();//Clears any existing tasks before adding new ones to ensure a fresh simulation.
         Random rand = new Random();
         
         
         
         int numTasks = 50; // Or 500, 750, 1000

         for (int i = 0; i < numTasks; i++) {
             int inputSize = (300 + rand.nextInt(301)) * 1024;
             int outputSize = (10 + rand.nextInt(11)) * 1024;
             int cycles = (210 + rand.nextInt(271)) * 1_000_000;
             int deadline = 30 + rand.nextInt(31);
             taskList.add(new Task("T" + i, inputSize, outputSize, cycles, deadline));
         }
        for (Task t : taskList) {
            Log.printLine(t.toString());
        }
    }

    private static void buildFogNodeWrappers() {//This wraps each Fog Device (FN) into a FogNodeWrapper to include power, capacity, and quota info.
        for (FogDevice d : fogDevices) {
            //Only selects devices whose name starts with "fn-" (fog nodes, not cloud).
            if (d.getName().startsWith("fn-")) {
                int id = d.getId();
                double txPower = 0.1;
                double idle = 2.0;
                double busy = 3.5;
                double capacity = d.getHost().getTotalMips();
                int quota = 10;
                //Sets power characteristics and computes processing capacity from the host. Each FN can handle up to 10 tasks (quota = 10).
                fnList.add(new FogNodeWrapper(id, txPower, idle, busy, capacity, quota));
                //Adds each wrapped fog node to fnList.
            }
        }
        // Add a check to confirm fnList is populated
        if (fnList.isEmpty()) {
            Log.printLine("WARNING: fnList is empty after building wrappers. Check naming conventions!");
        } else {
            for (FogNodeWrapper d : fnList) {
                Log.printLine(d.toString());
            }
        }
    }
    private static void createFogDevices(int userId) {
        fogDevices.clear();//Clears and prepares the fog devices list.
        Random rand = new Random();
        int fogNodes = 5;
     // Create the cloud device FIRST, as it's often the parent of others
        // Ensure its name is exactly "cloud" (lowercase, no extra spaces)
        FogDevice cloud = createFogDevice("cloud", 100000, 100000); // Example MIPS and RAM
        cloud.setParentId(-1); // Cloud usually has no parent
        fogDevices.add(cloud);//Creates a powerful cloud device that will act as the root.
      

        for (int i = 0; i < fogNodes ; i++) {
            String name = "fn-" + i;//Creates fog nodes named fn-0, fn-1, ..., fn-4.

            int vru = 50 + rand.nextInt(451);              // U[50, 500]
            int ghz = 6 + rand.nextInt(5);                 // U[6, 10]
            int mips = ghz * 1000;//Randomizes RAM (50–500), CPU speed (6–10 GHz), and converts to MIPS.
            @SuppressWarnings("unused")
			double power = 0.35 + 0.20 * rand.nextDouble();// U[0.35, 0.55]

            FogDevice fn = createFogDevice(name, mips, vru);
            fn.setParentId(cloud.getId()); // Set cloud as parent
            fn.setUplinkLatency(100.0); // Example latency to cloud
            fogDevices.add(fn);//Each FN is created and connected to the cloud.
            
            

            
        }
    }

    private static FogDevice createFogDevice(String name, long mips, int ram) {//Factory method to create a FogDevice.
        List<Pe> peList = List.of(new Pe(0, new PeProvisionerSimple(mips)));//Creates a processing element with provisioned MIPS.

        PowerHost host = new PowerHost(//Wraps the PE in a PowerHost with RAM, bandwidth, scheduler, and power model.
            FogUtils.generateEntityId(),
            new RamProvisionerSimple(ram),
            new BwProvisionerSimple(10000),
            1000000,
            peList,
            new StreamOperatorScheduler(peList),
            new FogLinearPowerModel(3.5, 2.0)
        );

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
            "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0
        );//Describes the characteristics of the Fog device.

        FogDevice device = null;
        try {
            device = new FogDevice(name, characteristics,
                    new AppModuleAllocationPolicy(Collections.singletonList(host)),
                    new LinkedList<>(), 10, 10000, 10000, 0.0, 0.01);//Constructs the FogDevice using the host, characteristics, and default allocation policy.
        } catch (Exception e) {
            e.printStackTrace();
        }
        return device;//Returns the created fog device object.
    }


    private static Application createApplication(String appId, int userId) {
    	//Creates a new application with given ID and user ID.
        Application app = Application.createApplication(appId, userId);
        
        //Defines three logical modules of the application (representing microservices).
        app.addAppModule("ClientModule", 10);
        app.addAppModule("ProcessingModule", 10);
        app.addAppModule("StorageModule", 10);
        
        //Adds data flow edges representing communication between modules and devices (sensor → client → processor → storage/response).
        app.addAppEdge("IoTSensor", "ClientModule", 400000, 300, "IoTData", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("ClientModule", "ProcessingModule", 480000000, 500, "ProcessedData", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("ProcessingModule", "StorageModule", 20000, 100, "StorageData", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("ProcessingModule", "ClientModule", 20000, 50, "Response", Tuple.DOWN, AppEdge.MODULE);
        app.addAppEdge("ClientModule", "IoTActuator", 100, 20, "Actuation", Tuple.DOWN, AppEdge.ACTUATOR);

        //Defines how incoming data types ("tuples") get transformed through the modules.
        app.addTupleMapping("ClientModule", "IoTData", "ProcessedData", new FractionalSelectivity(1.0));
        app.addTupleMapping("ProcessingModule", "ProcessedData", "StorageData", new FractionalSelectivity(1.0));
        app.addTupleMapping("ProcessingModule", "ProcessedData", "Response", new FractionalSelectivity(1.0));
        app.addTupleMapping("ClientModule", "Response", "Actuation", new FractionalSelectivity(1.0));

        AppLoop loop1 = new AppLoop(List.of("IoTSensor", "ClientModule", "ProcessingModule", "ClientModule", "IoTActuator"));
        app.setLoops(List.of(loop1));
        return app;//Defines a control loop (end-to-end data path). Returns the final application object.
    }
}






































package org.fog.test.perfeval;

import org.fog.application.Application;//Represents an iFogSim application.
import org.fog.entities.FogDevice;// Represents fog nodes/devices.
import org.fog.placement.ModulePlacement;//Superclass used for defining module placement strategies.
import java.util.*;



public class ModulePlacementMETO extends ModulePlacement {
	//This class defines a custom module placement policy for the METO algorithm, extending iFogSim's ModulePlacement base class.
    private Map<Integer, Integer> taskToFNMap;//Maps each Task ID to a selected Fog Node (FN) index (from METO output).
    private List<Task> taskList;// List of task objects.
    private List<FogDevice> fogDevices;//All fog devices available in the simulation.
    private List<FogNodeWrapper> fnList;//List of fog nodes wrapped with extra metadata (e.g., resource info).

    public ModulePlacementMETO(List<FogDevice> fogDevices, Application application,
                                List<Task> taskList, List<FogNodeWrapper> fnList,
                                Map<Integer, Integer> taskToFNMap) {
        super();//Calls the parent constructor ModulePlacement().
        this.fogDevices = fogDevices;
        this.taskList = taskList;
        this.fnList = fnList;
        this.taskToFNMap = taskToFNMap;
        //Initializes module-device and instance-count mappings.
        this.setModuleToDeviceMap(new HashMap<String, List<Integer>>());
        this.setModuleInstanceCountMap(new HashMap<Integer, Map<String, Integer>>());
        mapModules();//Calls the core placement function to assign modules based on METO's output.
    }

   @Override
    protected void mapModules() {
        for (FogDevice device : fogDevices) {
            if (device.getName().startsWith("cloud")) {//If the device is a cloud node, attach the StorageModule.
                addModuleToDevice("StorageModule", device.getName());
            } else if (device.getName().startsWith("fn-")) {
                addModuleToDevice("ProcessingModule", device.getName());//ProcessingModule: Handles computation.
                addModuleToDevice("ClientModule", device.getName());//ClientModule: Manages interaction with tasks.
            }
        }

        for (Map.Entry<Integer, Integer> entry : taskToFNMap.entrySet()) {
            Task task = taskList.get(entry.getKey());
            FogNodeWrapper fn = fnList.get(entry.getValue());

            for (FogDevice device : fogDevices) {
                if (device.getId() == fn.id) {
                	//Identifies the FogDevice corresponding to the FogNodeWrapper using its id.
                    System.out.println("[METO Placement] Assigning Task " + task.id +
                            " to FN Device " + device.getName());
                 // ✅ Add task module to device
                    String taskModuleName = "TaskModule-" + task.id;
                    addModuleToDevice(taskModuleName, device.getName());
                }
            }
        }
    }

    private void addModuleToDevice(String moduleName, String deviceName) {//Associates a module with a fog device.
        int deviceId = -1;
        for (FogDevice device : fogDevices) {
            if (device.getName().equals(deviceName)) {//Finds the deviceId of the device by name.
                deviceId = device.getId();
                break;
            }
        }
        if (deviceId == -1) return;//If no device is found, exit early.

        if (!getModuleToDeviceMap().containsKey(moduleName)) {
            getModuleToDeviceMap().put(moduleName, new ArrayList<>());//Ensure the module entry exists in the map.
        }
        getModuleToDeviceMap().get(moduleName).add(deviceId);//Adds this device to the list of devices hosting the module.
       
    }
} 




    
