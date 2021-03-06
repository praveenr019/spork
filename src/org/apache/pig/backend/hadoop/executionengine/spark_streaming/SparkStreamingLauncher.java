package org.apache.pig.backend.hadoop.executionengine.spark_streaming;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.pig.PigConstants;
import org.apache.pig.PigException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.Launcher;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.MRCompiler;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.MROperPlan;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.POPackageAnnotator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POCache;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PODistinct;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POFilter;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POForEach;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POGlobalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLimit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POSort;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStore;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POUnion;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.PlanHelper;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter.DistinctConverter;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter.FilterConverter;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter.LimitConverter;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter.LoadConverter;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter.POConverter;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter.StoreConverter;
import org.apache.pig.data.SchemaTupleBackend;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.tools.pigstats.PigStats;
import org.apache.pig.tools.pigstats.SparkStats;
import org.apache.spark.rdd.RDD;
import org.apache.spark.scheduler.JobLogger;
import org.apache.spark.scheduler.StatsReportListener;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.DStream;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.StreamingContext;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

/**
 * @author billg
 */
public class SparkStreamingLauncher extends Launcher {

    private static final Log LOG = LogFactory.getLog(SparkStreamingLauncher.class);

    // Our connection to Spark. It needs to be static so that it can be reused across jobs, because a
    // new SparkLauncher gets created for each job.
    private static JavaStreamingContext sparkContext = null;

    // An object that handle cache calls in the operator graph. This is again static because we want
    // it to be shared across SparkLaunchers. It gets cleared whenever we close the SparkContext.
   
    @Override
    public PigStats launchPig(PhysicalPlan physicalPlan, String grpName, PigContext pigContext) throws Exception {
        LOG.info("!!!!!!!!!!  Launching Spark (woot) !!!!!!!!!!!!");
        LOG.debug(physicalPlan);
        Configuration c = SparkUtil.newJobConf(pigContext);
        c.set(PigConstants.LOCAL_CODE_DIR,System.getProperty("java.io.tmpdir"));

        SchemaTupleBackend.initialize(c, pigContext);
/////////
// stolen from MapReduceLauncher
        MRCompiler mrCompiler = new MRCompiler(physicalPlan, pigContext);
        mrCompiler.compile();
        MROperPlan plan = mrCompiler.getMRPlan();
        POPackageAnnotator pkgAnnotator = new POPackageAnnotator(plan);
        pkgAnnotator.visit();
//        // this one: not sure
//        KeyTypeDiscoveryVisitor kdv = new KeyTypeDiscoveryVisitor(plan);
//        kdv.visit();

/////////

        startSparkIfNeeded();

        // initialize the supported converters
        Map<Class<? extends PhysicalOperator>, POConverter> convertMap =
                new HashMap<Class<? extends PhysicalOperator>, POConverter>();

        convertMap.put(POLoad.class,    new LoadConverter(pigContext, physicalPlan, sparkContext));
        convertMap.put(POStore.class,   new StoreConverter(pigContext));
//        convertMap.put(POForEach.class, new ForEachConverter());
        convertMap.put(POFilter.class,  new FilterConverter());
//        convertMap.put(POPackage.class, new PackageConverter());
//        convertMap.put(POCache.class,   cacheConverter);
//        convertMap.put(POLocalRearrange.class,  new LocalRearrangeConverter());
//        convertMap.put(POGlobalRearrange.class, new GlobalRearrangeConverter());
        convertMap.put(POLimit.class, new LimitConverter());
        convertMap.put(PODistinct.class, new DistinctConverter());
//        convertMap.put(POUnion.class, new UnionConverter(sparkContext));
//        convertMap.put(POSort.class, new SortConverter());
//        convertMap.put(POSplit.class, new SplitConverter());

        Map<OperatorKey, JavaDStream<Tuple>> rdds = new HashMap<OperatorKey, JavaDStream<Tuple>>();

        SparkStats stats = new SparkStats();
        LinkedList<POStore> stores = PlanHelper.getPhysicalOperators(physicalPlan, POStore.class);
        for (POStore poStore : stores) {
            physicalToRDD(physicalPlan, poStore, rdds, convertMap);
            stats.addOutputInfo(poStore, 1, 1, true, c); // TODO: use real values
        }
        sparkContext.start();
        return stats;
    }

    private static void startSparkIfNeeded() throws PigException {
        if (sparkContext == null) {
            String master = System.getenv("SPARK_MASTER");
            if (master == null) {
                LOG.info("SPARK_MASTER not specified, using \"local\"");
                master = "local";
            }

            String sparkHome = System.getenv("SPARK_HOME"); // It's okay if this is null for local mode
            String sparkJarsSetting = System.getenv("SPARK_JARS");
            String pigJar = System.getenv("SPARK_PIG_JAR");
            if(pigJar == null) {
                pigJar = "build/pig-0.12.0-SNAPSHOT-withouthadoop.jar";
            }
            String[] sparkJars = sparkJarsSetting == null ? new String[]{} : sparkJarsSetting.split(",");

            // TODO: Don't hardcode this JAR
            List<String> jars = Lists.asList(pigJar, sparkJars);

            if (!master.startsWith("local") && !master.equals("yarn-client")) {
                // Check that we have the Mesos native library and Spark home are set
                if (sparkHome == null) {
                    System.err.println("You need to set SPARK_HOME to run on a Mesos cluster!");
                    throw new PigException("SPARK_HOME is not set");
                }
                /*
                 * if (System.getenv("MESOS_NATIVE_LIBRARY") == null) {
                
                    System.err.println("You need to set MESOS_NATIVE_LIBRARY to run on a Mesos cluster!");
                    throw new PigException("MESOS_NATIVE_LIBRARY is not set");
                }
                
                // Tell Spark to use Mesos in coarse-grained mode (only affects Spark 0.6+; no impact on others)
                System.setProperty("spark.mesos.coarse", "true");
                */
            }
            
            // For coarse-grained Mesos mode, tell it an upper bound on how many cores to grab in total;
            // we conservatively set this to 32 unless the user set the SPARK_MAX_CPUS environment variable.
            if (System.getenv("SPARK_MAX_CPUS") != null) {
                int maxCores = 32;
                maxCores = Integer.parseInt(System.getenv("SPARK_MAX_CPUS"));
                System.setProperty("spark.cores.max", "" + maxCores);
            }
            System.setProperty("spark.cores.max", "1" );
            System.setProperty("spark.executor.memory", "" + "2g");
            JavaStreamingContext javaContext = new JavaStreamingContext(master, "Spork", new Duration(5000), sparkHome, jars.toArray(new String[jars.size()]));
            sparkContext = javaContext;
        }
    }

    // You can use this in unit tests to stop the SparkContext between tests.
    static void stopSpark() {
        if (sparkContext != null) {
            sparkContext.stop();
            sparkContext = null;
        }
    }

    private void physicalToRDD(PhysicalPlan plan, PhysicalOperator physicalOperator,
                               Map<OperatorKey, JavaDStream<Tuple>> rdds,
                               Map<Class<? extends PhysicalOperator>, POConverter> convertMap)
            throws IOException {

        JavaDStream nextRDD = null;
        List<PhysicalOperator> predecessors = plan.getPredecessors(physicalOperator);
        List<JavaDStream<Tuple>> predecessorRdds = Lists.newArrayList();
        if (predecessors!=null) {
            for (PhysicalOperator predecessor : predecessors) {
                physicalToRDD(plan, predecessor, rdds, convertMap);
                predecessorRdds.add(rdds.get(predecessor.getOperatorKey()));
            }
        }

        POConverter converter = convertMap.get(physicalOperator.getClass());
        if (converter == null) {
            throw new IllegalArgumentException("Spork unsupported PhysicalOperator: " + physicalOperator);
        }

        LOG.info("Converting operator " + physicalOperator.getClass().getSimpleName()+" "+physicalOperator);
        nextRDD = converter.convert(predecessorRdds, physicalOperator);

        if (POStore.class.equals(physicalOperator.getClass())) {
            return;
        }

        if (nextRDD == null) {
            throw new IllegalArgumentException("RDD should not be null after PhysicalOperator: " + physicalOperator);
        }

        rdds.put(physicalOperator.getOperatorKey(), nextRDD);
    }

    @Override
    public void explain(PhysicalPlan pp, PigContext pc, PrintStream ps, String format, boolean verbose)
            throws IOException { }
}
