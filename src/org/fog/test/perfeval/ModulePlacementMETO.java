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


