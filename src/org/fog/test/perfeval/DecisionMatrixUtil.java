
package org.fog.test.perfeval;

import java.util.*;

public class DecisionMatrixUtil {

    // Constructs decision matrix for a task: {latency, deviceEnergy} for each FN
    public static Map<Task, double[][]> constructTaskMatrices(List<Task> tasks, List<FogNodeWrapper> fogNodes) {
        Map<Task, double[][]> taskMatrices = new HashMap<>();

        for (Task task : tasks) {
            double[][] matrix = new double[fogNodes.size()][2];
            for (int i = 0; i < fogNodes.size(); i++) {
                FogNodeWrapper fn = fogNodes.get(i);

                double gain = computeChannelGain(70.7); // for homogenoues setup If devices and FNs are uniformly distributed, the average distance between an IoT device and a randomly selected FNs
               
                
                double uplinkRate = computeUplinkRate( gain);//uplinkRate simulates bandwidth between device and fog node.
                double txTime = task.inputSize / uplinkRate;//txTime: Time to send task data. transmission time
                double compTime = (double) (task.cycles / fn.capacity)*1000;
                double rxTime = task.outputSize / uplinkRate; // Assuming symmetric link for simplicity,rxTime: Time to receive results back.

                double latency = txTime + compTime + rxTime;//latency: Total delay experienced by the task.
                double deviceEnergy = fn.txPower * (txTime + rxTime);//deviceEnergy: Energy used during transmission.

                matrix[i][0] = latency;
                matrix[i][1] = deviceEnergy;
            }
            taskMatrices.put(task, matrix);//Each task is mapped to its decision matrix.
        }
        return taskMatrices;
    }

    // Constructs decision matrix for a FN: {fnEnergy, deadline} for each task
    public static Map<FogNodeWrapper, double[][]> constructFogNodeMatrices(List<Task> tasks, List<FogNodeWrapper> fogNodes) {
        Map<FogNodeWrapper, double[][]> fnMatrices = new HashMap<>();

        for (FogNodeWrapper fn : fogNodes) {
            double[][] matrix = new double[tasks.size()][2];
            for (int j = 0; j < tasks.size(); j++) {
                Task task = tasks.get(j);

                double gain = computeChannelGain(70.7); // Dummy again
                double uplinkRate = computeUplinkRate( gain);
                double txTime = task.inputSize / uplinkRate;
                double rxTime = task.outputSize / uplinkRate;
                double compTime = (double) (task.cycles / fn.capacity)*1000;

                double fnEnergy = fn.txPower * (txTime + rxTime) + compTime * fn.CompPower;//fnEnergy: Total energy used by the fog node.
                double deadline = task.deadline;//deadline: Time by which task must complete.
                
                

                matrix[j][0] = fnEnergy;
                matrix[j][1] = deadline;
            }
            fnMatrices.put(fn, matrix);
        }
        return fnMatrices;
    }

    // Normalization, CRITIC weights, and TOPSIS remain as defined
    public static double[][] normalizeMatrix(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] normalized = new double[rows][cols];

        for (int j = 0; j < cols; j++) {
            double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
            for (int i = 0; i < rows; i++) {
                min = Math.min(min, matrix[i][j]);
                max = Math.max(max, matrix[i][j]);
            }
            for (int i = 0; i < rows; i++) {
                normalized[i][j] = (max - min == 0) ? 0 : (matrix[i][j] - min) / (max - min);
            }//This rescales the matrix values to range between 0 and 1.
        }
        return normalized;
    }

    public static double[] computeCRITICWeights(double[][] matrix) {
        double[][] normalized = normalizeMatrix(matrix);
        int criteria = matrix[0].length;//col :here 2
        int alternatives = matrix.length;//rows:agents
        double[] stdDevs = new double[criteria];

        for (int j = 0; j < criteria; j++) {
            double mean = 0;
            for (int i = 0; i < alternatives; i++) mean += normalized[i][j];
            mean /= alternatives;
            double sumSq = 0;
            for (int i = 0; i < alternatives; i++) sumSq += Math.pow(normalized[i][j] - mean, 2);
            stdDevs[j] = Math.sqrt(sumSq / alternatives);//This reflects the contrast intensity (variance) of a criterion.
        }
        double[][] corr = new double[criteria][criteria]; // Measures Pearson correlation between criteria

     // Calculate mean of each criterion (column)
        double[] means = new double[criteria];
        for (int j = 0; j < criteria; j++) {
         double sum = 0;
         for (int i = 0; i < alternatives; i++) {
             sum += normalized[i][j];
         }
         means[j] = sum / alternatives;
        }

     // Compute Pearson correlation
     for (int i = 0; i < criteria; i++) {
         for (int j = 0; j < criteria; j++) {
             double num = 0, denom1 = 0, denom2 = 0;
             for (int k = 0; k < alternatives; k++) {
                 double xi = normalized[k][i] - means[i];
                 double xj = normalized[k][j] - means[j];
                 num += xi * xj;
                 denom1 += xi * xi;
                 denom2 += xj * xj;
             }
             corr[i][j] = (denom1 == 0 || denom2 == 0) ? 0 : num / Math.sqrt(denom1 * denom2);
         }
     }



//        double[][] corr = new double[criteria][criteria];//Measures redundancy or interdependence between criteria.
//        for (int i = 0; i < criteria; i++) {
//            for (int j = 0; j < criteria; j++) {
//                double num = 0, denom1 = 0, denom2 = 0;
//                for (int k = 0; k < alternatives; k++) {
//                    num += normalized[k][i] * normalized[k][j];
//                    denom1 += normalized[k][i] * normalized[k][i];                                               this is for cosine correaltion
//                    denom2 += normalized[k][j] * normalized[k][j];
//                }//This is the formula for cosine similarity,
//                corr[i][j] = (denom1 == 0 || denom2 == 0) ? 0 : num / Math.sqrt(denom1 * denom2);
//            }//Diagonal is always 1 (a criterion is perfectly correlated with itself).,Off-diagonals tell how much info is shared.
//        }

        double[] info = new double[criteria];
        double sumInfo = 0;
        for (int k = 0; k < criteria; k++) {
            double sumCorr = 0;
            for (int j = 0; j < criteria; j++) sumCorr += (1 - corr[k][j]);
            info[k] = stdDevs[k] * sumCorr;
            sumInfo += info[k];
        }

        double[] weights = new double[criteria];
        for (int k = 0; k < criteria; k++) weights[k] = (sumInfo == 0) ? 0 : info[k] / sumInfo;
        return weights;
    }

    public static List<Integer> applyTOPSIS(double[][] matrix, double[] weights) {
    	//Ranks the alternatives based on closeness to ideal solution.
        double[][] normalized = normalizeMatrix(matrix);
        int rows = normalized.length;
        int cols = normalized[0].length;

        double[][] weighted = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                weighted[i][j] = normalized[i][j] * weights[j];

        double[] idealPos = new double[cols], idealNeg = new double[cols];
        Arrays.fill(idealPos, Double.MIN_VALUE);
        Arrays.fill(idealNeg, Double.MAX_VALUE);

        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                idealPos[j] = Math.max(idealPos[j], weighted[i][j]);
                idealNeg[j] = Math.min(idealNeg[j], weighted[i][j]);
            }
        }

        Map<Integer, Double> closenessMap = new HashMap<>();
        for (int i = 0; i < rows; i++) {
            double dPos = 0, dNeg = 0;
            for (int j = 0; j < cols; j++) {
                dPos += Math.pow(weighted[i][j] - idealPos[j], 2);
                dNeg += Math.pow(weighted[i][j] - idealNeg[j], 2);
            }
            dPos = Math.sqrt(dPos);
            dNeg = Math.sqrt(dNeg);
            closenessMap.put(i, dNeg / (dNeg + dPos));
            //key = ID or index of the alternative (e.g., Fog Node ID),
            //value = Closeness score
        }

        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(closenessMap.entrySet());
        //Converts the map into a list of entries:
        //We do this so we can sort them.
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        //This is a lambda(anonymous) expression used to sort a list of map entries in descending order of values
        //Double.compare(b, a) gives descending sort.
        //a and b are two entries (like (0, 0.65) and (1, 0.88))
        //a.getValue() = the closeness score of entry a
        //b.getValue() = the closeness score of entry b

        List<Integer> ranked = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : sorted)
            ranked.add(entry.getKey());//Now we only care about the IDs or indices of the best alternatives, not the scores.
            //So we extract just the keys (e.g., node IDs) in order.

        return ranked;
    }

    // --- Dummy formulas for channel gain and uplink rate (replace with actual values or distances) ---
    public static double computeChannelGain(double distance) {//distance is in range 50-500
        double pathLossDb = 38.02 + 20 * Math.log10(distance);
        return Math.pow(10, -pathLossDb / 10);
    }

    public static double computeUplinkRate( double gain) {
    	 Random rand = new Random();
        double bandwidth = 10e6;
        double noisePower = 1e-10;
        double power = 1+ rand.nextDouble();;
        return bandwidth * Math.log(1 + (power * gain / noisePower)) / Math.log(2);
        //This is the Shannon Capacity Formula,
        //C = B * log2(1 + S/N)

    }
}
