package org.apache.pig.backend.hadoop.executionengine.spark_streaming.converter;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.Job;
import org.apache.pig.LoadFunc;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.backend.hadoop.executionengine.spark_streaming.SparkUtil;
import org.apache.pig.data.DefaultTuple;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import scala.Function1;
import scala.runtime.AbstractFunction1;

import com.google.common.collect.Lists;

/**
 * Converter that loads data via POLoad and converts it to RRD&lt;Tuple>. Abuses the interface a bit
 * in that there is no inoput RRD to convert in this case. Instead input is the source path of the
 * POLoad.
 *
 * @author billg
 */
@SuppressWarnings({ "serial"})
public class LoadConverter implements POConverter<Tuple, Tuple, POLoad> {

	private static final Function1<String, Tuple> TO_VALUE_FUNCTION = new ToTupleFunction();
     
    private PigContext pigContext;
    private PhysicalPlan physicalPlan;
    private JavaStreamingContext sparkContext;

    public LoadConverter(PigContext pigContext, PhysicalPlan physicalPlan, JavaStreamingContext sparkContext2) {
        this.pigContext = pigContext;
        this.physicalPlan = physicalPlan;
        this.sparkContext = sparkContext2;
    }

    @Override
    public JavaDStream<Tuple> convert(List<JavaDStream<Tuple>> predecessorRdds, POLoad poLoad) throws IOException {
//        if (predecessors.size()!=0) {
//            throw new RuntimeException("Should not have predecessors for Load. Got : "+predecessors);
//        }

        JobConf loadJobConf = SparkUtil.newJobConf(pigContext);
        configureLoader(physicalPlan, poLoad, loadJobConf);

        // don't know why but just doing this cast for now
        JavaDStream<String> hadoopRDD = sparkContext.textFileStream(poLoad.getLFile().getFileName());
       
        // map to get just RDD<Tuple>
        return new JavaDStream<Tuple>(hadoopRDD.dstream().map(TO_VALUE_FUNCTION,SparkUtil.getManifest(Tuple.class)), SparkUtil.getManifest(Tuple.class));
    }

    private static class ToTupleFunction extends AbstractFunction1<String, Tuple>
            implements Function1<String, Tuple>, Serializable {

        @Override
        public Tuple apply(String v1) {
        	DefaultTuple var=new DefaultTuple();
			var.append(v1);
        	return var;
        }
    }

    /**
     * stolen from JobControlCompiler
     * TODO: refactor it to share this
     * @param physicalPlan
     * @param poLoad
     * @param jobConf
     * @return
     * @throws java.io.IOException
     */
    private static JobConf configureLoader(PhysicalPlan physicalPlan,
                                           POLoad poLoad, JobConf jobConf) throws IOException {

        Job job = new Job(jobConf);
        LoadFunc loadFunc = poLoad.getLoadFunc();

        loadFunc.setLocation(poLoad.getLFile().getFileName(), job);

        // stolen from JobControlCompiler
        ArrayList<FileSpec> pigInputs = new ArrayList<FileSpec>();
        //Store the inp filespecs
        pigInputs.add(poLoad.getLFile());

        ArrayList<List<OperatorKey>> inpTargets = Lists.newArrayList();
        ArrayList<String> inpSignatures = Lists.newArrayList();
        ArrayList<Long> inpLimits = Lists.newArrayList();
        //Store the target operators for tuples read
        //from this input
        List<PhysicalOperator> loadSuccessors = physicalPlan.getSuccessors(poLoad);
        List<OperatorKey> loadSuccessorsKeys = Lists.newArrayList();
        if(loadSuccessors!=null){
            for (PhysicalOperator loadSuccessor : loadSuccessors) {
                loadSuccessorsKeys.add(loadSuccessor.getOperatorKey());
            }
        }
        inpTargets.add(loadSuccessorsKeys);
        inpSignatures.add(poLoad.getSignature());
        inpLimits.add(poLoad.getLimit());

        jobConf.set("pig.inputs", ObjectSerializer.serialize(pigInputs));
        jobConf.set("pig.inpTargets", ObjectSerializer.serialize(inpTargets));
        jobConf.set("pig.inpSignatures", ObjectSerializer.serialize(inpSignatures));
        jobConf.set("pig.inpLimits", ObjectSerializer.serialize(inpLimits));

        return jobConf;
    }
}
