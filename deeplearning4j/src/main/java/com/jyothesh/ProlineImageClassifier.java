package com.jyothesh;

import java.io.File;
import java.util.List;
import java.util.Random;

import org.apache.commons.io.FilenameUtils;
import org.datavec.api.io.filters.BalancedPathFilter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.datavec.image.transform.FlipImageTransform;
import org.datavec.image.transform.ImageTransform;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.datasets.iterator.MultipleEpochsIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.LearningRatePolicy;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.distribution.Distribution;
import org.deeplearning4j.nn.conf.distribution.GaussianDistribution;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.LocalResponseNormalization;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.NetSaverLoaderUtils;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProlineImageClassifier {
	protected static final Logger log = LoggerFactory.getLogger(ProlineImageClassifier.class);
	protected static int height = 100;
	protected static int width = 100;
	protected static int channels = 3;
	protected static int numExamples = 80;
	protected static int numLabels = 4;
	protected static int batchSize = 20;

	protected static long seed = 42;
	protected static Random rng = new Random(seed);
	protected static int listenerFreq = 1;
	protected static int iterations = 1;
	protected static int epochs = 100;
	protected static double splitTrainTest = 0.8;
	protected static int nCores = 4;
	protected static boolean save = false;

	public void run(String[] args) throws Exception {

		log.info("Load data....");
		ParentPathLabelGenerator labelMaker = new ParentPathLabelGenerator();
		File mainPath = new File(System.getProperty("user.dir"), "src/main/resources/prolineImages/");
		FileSplit fileSplit = new FileSplit(mainPath, NativeImageLoader.ALLOWED_FORMATS, rng);
		BalancedPathFilter pathFilter = new BalancedPathFilter(rng, labelMaker, numExamples, numLabels, batchSize);

		InputSplit[] inputSplit = fileSplit.sample(pathFilter, numExamples * (1 + splitTrainTest), numExamples * (1 - splitTrainTest));
		InputSplit trainData = inputSplit[0];
		InputSplit testData = inputSplit[1];

		ImageTransform flipTransform = new FlipImageTransform(rng);
		DataNormalization scaler = new ImagePreProcessingScaler(0, 1);

		log.info("Build model....");
		MultiLayerNetwork network = alexnetModel();
		network.init();
		network.setListeners(new ScoreIterationListener(listenerFreq));

		/**
		 * Data Setup -> define how to load data into net:
		 *  - recordReader = the reader that loads and converts image data pass in inputSplit to initialize
		 *  - dataIter = a generator that only loads one batch at a time into memory to save memory
		 *  - trainIter = uses MultipleEpochsIterator to ensure model runs through the data for all epochs
		 **/
		ImageRecordReader recordReader = new ImageRecordReader(height, width, channels, labelMaker);
		DataSetIterator dataIter;
		MultipleEpochsIterator trainIter;


		log.info("Train model....");
		recordReader.initialize(trainData, null);
		dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
		scaler.fit(dataIter);
		dataIter.setPreProcessor(scaler);
		trainIter = new MultipleEpochsIterator(epochs, dataIter, nCores);
		network.fit(trainIter);

		System.out.print("\nTraining on transformation: " + flipTransform.getClass().toString() + "\n\n");
		recordReader.initialize(trainData, flipTransform);
		dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
		scaler.fit(dataIter);
		dataIter.setPreProcessor(scaler);
		trainIter = new MultipleEpochsIterator(epochs, dataIter, nCores);
		network.fit(trainIter);

		log.info("Evaluate model....");
		recordReader.initialize(testData);
		dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
		scaler.fit(dataIter);
		dataIter.setPreProcessor(scaler);
		Evaluation eval = network.evaluate(dataIter);
		log.info(eval.stats(true));

		// Example on how to get predict results with trained model
		dataIter.reset();
		DataSet testDataSet = dataIter.next();
		String expectedResult = testDataSet.getLabelName(0);
		List<String> predict = network.predict(testDataSet);
		String modelResult = predict.get(0);
		System.out.print("\nFor a single example that is labeled " + expectedResult + " the model predicted " + modelResult + "\n\n");

		if (save) {
			log.info("Save model....");
			String basePath = FilenameUtils.concat(System.getProperty("user.dir"), "src/main/resources/");
			NetSaverLoaderUtils.saveNetworkAndParameters(network, basePath);
			NetSaverLoaderUtils.saveUpdators(network, basePath);
		}
		log.info("****************Example finished********************");
	}

	private ConvolutionLayer convInit(String name, int in, int out, int[] kernel, int[] stride, int[] pad, double bias) {
		return new ConvolutionLayer.Builder(kernel, stride, pad).name(name).nIn(in).nOut(out).biasInit(bias).build();
	}

	private ConvolutionLayer conv3x3(String name, int out, double bias) {
		return new ConvolutionLayer.Builder(new int[]{3,3}, new int[] {1,1}, new int[] {1,1}).name(name).nOut(out).biasInit(bias).build();
	}

	private ConvolutionLayer conv5x5(String name, int out, int[] stride, int[] pad, double bias) {
		return new ConvolutionLayer.Builder(new int[]{5,5}, stride, pad).name(name).nOut(out).biasInit(bias).build();
	}

	private SubsamplingLayer maxPool(String name,  int[] kernel) {
		return new SubsamplingLayer.Builder(kernel, new int[]{2,2}).name(name).build();
	}

	private DenseLayer fullyConnected(String name, int out, double bias, double dropOut, Distribution dist) {
		return new DenseLayer.Builder().name(name).nOut(out).biasInit(bias).dropOut(dropOut).dist(dist).build();
	}

	public MultiLayerNetwork alexnetModel() {

		double nonZeroBias = 1;
		double dropOut = 0.5;

		MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
				.seed(seed)
				.weightInit(WeightInit.DISTRIBUTION)
				.dist(new NormalDistribution(0.0, 0.01))
				.activation(Activation.RELU)
				.updater(Updater.NESTEROVS)
				.iterations(iterations)
				.gradientNormalization(GradientNormalization.RenormalizeL2PerLayer) // normalize to prevent vanishing or exploding gradients
				.optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
				.learningRate(1e-2)
				.biasLearningRate(1e-2*2)
				.learningRateDecayPolicy(LearningRatePolicy.Step)
				.lrPolicyDecayRate(0.1)
				.lrPolicySteps(100000)
				.regularization(true)
				.l2(5 * 1e-4)
				.momentum(0.9)
				.miniBatch(false)
				.list()
				.layer(0, convInit("cnn1", channels, 96, new int[]{11, 11}, new int[]{4, 4}, new int[]{3, 3}, 0))
				.layer(1, new LocalResponseNormalization.Builder().name("lrn1").build())
				.layer(2, maxPool("maxpool1", new int[]{3,3}))
				.layer(3, conv5x5("cnn2", 256, new int[] {1,1}, new int[] {2,2}, nonZeroBias))
				.layer(4, new LocalResponseNormalization.Builder().name("lrn2").build())
				.layer(5, maxPool("maxpool2", new int[]{3,3}))
				.layer(6,conv3x3("cnn3", 384, 0))
				.layer(7,conv3x3("cnn4", 384, nonZeroBias))
				.layer(8,conv3x3("cnn5", 256, nonZeroBias))
				.layer(9, maxPool("maxpool3", new int[]{3,3}))
				.layer(10, fullyConnected("ffn1", 4096, nonZeroBias, dropOut, new GaussianDistribution(0, 0.005)))
				.layer(11, fullyConnected("ffn2", 4096, nonZeroBias, dropOut, new GaussianDistribution(0, 0.005)))
				.layer(12, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
						.name("output")
						.nOut(numLabels)
						.activation(Activation.SOFTMAX)
						.build())
				.backprop(true)
				.pretrain(false)
				.setInputType(InputType.convolutional(height, width, channels))
				.build();

		return new MultiLayerNetwork(conf);

	}

	public static void main(String[] args) throws Exception {
		new ProlineImageClassifier().run(args);
	}

}