package org.fog.test.perfeval;

import java.util.*;

public class METOMatcher {

    /**
     * Executes the Deferred Acceptance Algorithm (DAA) for METO
     * @param taskPrefs Preferences of each Task over FN (task -> ranked list of FN indices)
     * @param fnPrefs Preferences of each FN over Tasks (fn -> ranked list of Task indices)
     * @param taskQuota Number of FNs each task can be assigned to (should be 1)
     * @param fnQuotas Quotas for each FN (FN can accept up to Qi tasks)
     * @return Matching map: task index -> matched FN index
     */
    public static Map<Integer, Integer> runMatching(Map<Integer, List<Integer>> taskPrefs,
                                                    Map<Integer, List<Integer>> fnPrefs,
                                                    Map<Integer, Integer> fnQuotas) {
    	//Executes the matching process between tasks and Fog Nodes (FNs) based on mutual preferences.
        //taskPrefs: Preferences of each task over available FNs.
    	//fnPrefs: Preferences of each FN over tasks.
    	//fnQuotas: Capacity (quota) of each FN — how many tasks it can accept.
    	Map<Integer, Integer> taskToFN = new HashMap<>(); // Final output
        Map<Integer, Set<Integer>> fnToTasks = new HashMap<>();//Tracks current accepted tasks by each FN.
        Map<Integer, Set<Integer>> fnRejected = new HashMap<>();//Temporarily stores tasks rejected by each FN in the current round.

        for (Integer fn : fnPrefs.keySet()) {
            fnToTasks.put(fn, new HashSet<>());//Initializes empty sets for each FN’s accepted and rejected task lists.
            fnRejected.put(fn, new HashSet<>());
        }

        Set<Integer> freeTasks = new HashSet<>(taskPrefs.keySet());
        Map<Integer, Integer> taskNextProposal = new HashMap<>();//Tracks which FN a task will propose to next, based on its preference list index.
        taskPrefs.keySet().forEach(t -> taskNextProposal.put(t, 0));

        while (!freeTasks.isEmpty()) {
            Iterator<Integer> iter = freeTasks.iterator();//Keeps iterating until all tasks are matched.
            while (iter.hasNext()) {
                int task = iter.next();
                List<Integer> preferences = taskPrefs.get(task);
                if (taskNextProposal.get(task) >= preferences.size()) {//If a task has exhausted all preferences, remove it from the loop.
                    iter.remove();
                    continue;
                }
                int fn = preferences.get(taskNextProposal.get(task));//The FN tentatively accepts the task (adds to current list).
                taskNextProposal.put(task, taskNextProposal.get(task) + 1);

                fnToTasks.get(fn).add(task);

                // sort current tasks by FN preference
                List<Integer> ranked = new ArrayList<>(fnToTasks.get(fn));
                //fnToTasks.get(fn) gives the set of tasks currently proposed to this FN.
                ranked.sort(Comparator.comparingInt(t -> fnPrefs.get(fn).indexOf(t)));
                //Sorts the ranked list in the order of the FN's preference.
                //For each task t in the ranked list:
                //fnPrefs.get(fn): Gets the preference list of the current FN.
                //.indexOf(t): Finds the index of task t in this preference list.
                //Tasks with lower index values (i.e., higher preference) come first in the sorted list.
                //FN ranks all currently proposed tasks according to its own preference list.

                // prune if over quota
                while (ranked.size() > fnQuotas.get(fn)) {
                    int worst = ranked.get(ranked.size() - 1);
                    ranked.remove(ranked.size() - 1);
                    fnToTasks.get(fn).remove(worst);
                    fnRejected.get(fn).add(worst);//These rejected tasks are added to fnRejected.
                }
            }

            // Reassign freeTasks, Prepare for Next Round
            freeTasks.clear();
            for (Map.Entry<Integer, Set<Integer>> entry : fnRejected.entrySet()) {
                freeTasks.addAll(entry.getValue());
                entry.getValue().clear();
                
            }//Tasks rejected in this round become free again and will propose to their next preferred FN in the next iteration.
        }

        // Build taskToFN map,Final Assignment
        for (Map.Entry<Integer, Set<Integer>> entry : fnToTasks.entrySet()) {
            int fn = entry.getKey();
            for (int task : entry.getValue()) {
                taskToFN.put(task, fn);
            }//Converts the FN-side mapping (fnToTasks) into a task-to-FN map for return.
        }

        return taskToFN;
    }
}
