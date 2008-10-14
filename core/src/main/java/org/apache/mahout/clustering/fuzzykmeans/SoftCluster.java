/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.mahout.clustering.fuzzykmeans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.mahout.matrix.AbstractVector;
import org.apache.mahout.matrix.SparseVector;
import org.apache.mahout.matrix.Vector;
import org.apache.mahout.utils.DistanceMeasure;

public class SoftCluster {

  public static final String DISTANCE_MEASURE_KEY = "org.apache.mahout.clustering.kmeans.measure";

  public static final String CLUSTER_PATH_KEY = "org.apache.mahout.clustering.kmeans.path";

  public static final String CLUSTER_CONVERGENCE_KEY = "org.apache.mahout.clustering.kmeans.convergence";

  public static final String M_KEY = "org.apache.mahout.clustering.fuzzykmeans.m";

  private static float m = 2; //default value
  public static final double minimalValue = 0.0000000001; // using it for adding

  // exception
  // this value to any
  // zero valued
  // variable to avoid
  // divide by Zero

  private static int nextClusterId = 0;

  // this cluster's clusterId
  private final int clusterId;

  // the current center
  private Vector center = new SparseVector(0);

  // the current centroid is lazy evaluated and may be null
  private Vector centroid = null;

  // The Probability of belongingness sum
  private double pointProbSum = 0.0;

  // the total of all points added to the cluster
  private Vector weightedPointTotal = null;

  // has the centroid converged with the center?
  private boolean converged = false;

  private static DistanceMeasure measure;
  private static double convergenceDelta = 0;

  /**
   * Format the SoftCluster for output
   * 
   * @param cluster the Cluster
   * @return
   */
  public static String formatCluster(SoftCluster cluster) {
    return cluster.getIdentifier() + ": "
        + cluster.computeCentroid().asFormatString();
  }

  /**
   * Decodes and returns a SoftCluster from the formattedString
   * 
   * @param formattedString a String produced by formatCluster
   * @return
   */
  public static SoftCluster decodeCluster(String formattedString) {
    int beginIndex = formattedString.indexOf('[');
    String id = formattedString.substring(0, beginIndex);
    String center = formattedString.substring(beginIndex);
    if (id.startsWith("C") || id.startsWith("V")) {
      int clusterId = Integer.parseInt(formattedString.substring(1, beginIndex - 2));
      Vector clusterCenter = AbstractVector.decodeVector(center);

      SoftCluster cluster = new SoftCluster(clusterCenter, clusterId);
      cluster.converged = id.startsWith("V");
      return cluster;
    }
    return null;
  }

  /**
   * Configure the distance measure from the job
   * 
   * @param job the JobConf for the job
   */
  public static void configure(JobConf job) {
    try {
      final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
      Class<?> cl = ccl.loadClass(job.get(DISTANCE_MEASURE_KEY));
      measure = (DistanceMeasure) cl.newInstance();
      measure.configure(job);
      convergenceDelta = Double.parseDouble(job.get(CLUSTER_CONVERGENCE_KEY));
      nextClusterId = 0;
      m = Float.parseFloat(job.get(M_KEY)); 
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Configure the distance measure directly. Used by unit tests.
   * 
   * @param aMeasure the DistanceMeasure
   * @param aConvergenceDelta the delta value used to define convergence
   */
  public static void config(DistanceMeasure aMeasure, double aConvergenceDelta) {
    measure = aMeasure;
    convergenceDelta = aConvergenceDelta;
    nextClusterId = 0;
  }

  /**
   * Emit the point and its probability of belongingness to each cluster
   * 
   * @param point a point
   * @param clusters a List<SoftCluster>
   * @param values a Writable containing the input point and possible other
   *        values of interest (payload)
   * @param output the OutputCollector to emit into
   * @throws IOException
   */
  public static void emitPointProbToCluster(Vector point,
      List<SoftCluster> clusters, Text values,
      OutputCollector<Text, Text> output) throws IOException {
    List<Double> clusterDistanceList = new ArrayList<Double>();
    for (SoftCluster cluster : clusters) {
      clusterDistanceList.add(measure.distance(point, cluster.getCenter()));
    }

    for (int i = 0; i < clusters.size(); i++) {
      double probWeight = computeProbWeight(clusterDistanceList.get(i),
          clusterDistanceList);

      Text key = new Text(formatCluster(clusters.get(i)));
      Text value = new Text(Double.toString(probWeight) + ":"
          + values.toString());
      output.collect(key, value);
    }
  }

  /**
   * Output point with cluster info (Cluster and probability)
   * 
   * @param point a point
   * @param clusters a List<SoftCluster> to test
   * @param values a Writable containing the input point and possible other
   *        values of interest (payload)
   * @param output the OutputCollector to emit into
   * @throws IOException
   */
  public static void outputPointWithClusterProbabilities(Vector point,
      List<SoftCluster> clusters, Text values,
      OutputCollector<Text, Text> output) throws IOException {

    String outputKey = values.toString();
    StringBuffer outputValue = new StringBuffer("[");
    List<Double> clusterDistanceList = new ArrayList<Double>();

    for (SoftCluster cluster : clusters) {
      clusterDistanceList.add(measure.distance(point, cluster.getCenter()));
    }

    for (int i = 0; i < clusters.size(); i++) {
      double probWeight = computeProbWeight(clusterDistanceList.get(i),
          clusterDistanceList);
      outputValue.append(clusters.get(i).clusterId).append(':').append(
          probWeight).append(' ');
    }
    output.collect(new Text(outputKey.trim()), new Text(outputValue.toString()
        .trim()
        + "]"));
  }

  /**
   * Computes the probability of a point belonging to a cluster
   * 
   * @param clusterDistance
   * @param clusterDistanceList
   * @return
   */
  public static double computeProbWeight(double clusterDistance,
      List<Double> clusterDistanceList) {
    double denom = 0.0;
    if (clusterDistance == 0.0) {
      clusterDistance = minimalValue;
    }
    for (double eachCDist : clusterDistanceList) {
      if (eachCDist == 0)
        eachCDist = minimalValue;

      denom += Math.pow(clusterDistance / eachCDist, (double) 2 / (m - 1));

    }
    return 1.0 / denom;
  }

  /**
   * Compute the centroid
   * 
   * @return the new centroid
   */
  private Vector computeCentroid() {
    if (pointProbSum == 0)
      return weightedPointTotal;
    else if (centroid == null) {
      // lazy compute new centroid
      centroid = weightedPointTotal.divide(pointProbSum);
    }
    return centroid;
  }

  /**
   * Construct a new SoftCluster with the given point as its center
   * 
   * @param center the center point
   */
  public SoftCluster(Vector center) {
    super();
    this.clusterId = nextClusterId++;
    this.center = center;
    this.pointProbSum = 0;

    this.weightedPointTotal = center.like();
  }

  /**
   * Construct a new SoftCluster with the given point as its center
   * 
   * @param center the center point
   */
  public SoftCluster(Vector center, int clusterId) {
    super();
    this.clusterId = clusterId;
    this.center = center;
    this.pointProbSum = 0;
    this.weightedPointTotal = center.like();
  }

  @Override
  public String toString() {
    return getIdentifier() + " - " + center.asFormatString();
  }

  public String getIdentifier() {
    if (converged)
      return "V" + clusterId;
    else
      return "C" + clusterId;
  }

  /**
   * Add the point to the SoftCluster
   * 
   * @param point a point to add
   * @param ptProb
   */
  public void addPoint(Vector point, double ptProb) {
    centroid = null;
    pointProbSum += ptProb;
    if (weightedPointTotal == null)
      weightedPointTotal = point.copy().times(ptProb);
    else
      weightedPointTotal = point.times(ptProb).plus(weightedPointTotal);
  }

  /**
   * Add the point to the SoftCluster
   *
   * @param partialSumPtProb
   * @param delta a point to add
   */
  public void addPoints(double partialSumPtProb, Vector delta) {
    centroid = null;
    pointProbSum += partialSumPtProb;
    if (weightedPointTotal == null)
      weightedPointTotal = delta.copy();
    else
      weightedPointTotal = delta.plus(weightedPointTotal);
  }

  public Vector getCenter() {
    return center;
  }

  public double getPointProbSum() {
    return pointProbSum;
  }

  /**
   * Compute the centroid and set the center to it.
   */
  public void recomputeCenter() {
    center = computeCentroid();
    pointProbSum = 0;
    weightedPointTotal = center.like();
  }

  /**
   * Return if the cluster is converged by comparing its center and centroid.
   * 
   * @return if the cluster is converged
   */
  public boolean computeConvergence() {
    Vector centroid = computeCentroid();
    converged = measure.distance(centroid, center) <= convergenceDelta;
    return converged;
  }

  public Vector getWeightedPointTotal() {
    return weightedPointTotal;
  }

  public boolean isConverged() {
    return converged;
  }

  public int getClusterId() {
    return clusterId;
  }

  public static float getM() {
    return m;
  }



}
