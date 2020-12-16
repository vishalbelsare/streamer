package cea.streamer;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import com.google.common.io.Resources;

import cea.util.Log;
import cea.util.connectors.InfluxDBConnector;
import cea.util.connectors.RedisConnector;

/**
 * Demonstrates, using the high-level KStream DSL, how to implement the
 * WordCount program that computes a simple word occurrence histogram from an
 * input text.
 *
 * In this example, the input stream reads from a topic named
 * "streams-file-input", where the values of messages represent lines of text;
 * and the histogram output is written to topic "streams-wordcount-output" where
 * each record is an updated count of a single word.
 *
 * Before running this example you must create the source topic (e.g. via
 * bin/kafka-topics.sh --create ...) and write some data to it (e.g. via
 * bin-kafka-console-producer.sh). Otherwise you won't see any data arriving in
 * the output topic.
 */

public class Launcher {

	/**
	 * Launchs the streaming platform. It allows having separate processes, each of
	 * them to read and process a different input channel
	 * 
	 * @param origins Folder where the properties are
	 * @throws IOException
	 */
	public void launch(String[] origins) throws IOException {

		InfluxDBConnector.init();
		// cleaning the database in InfluxDB
		InfluxDBConnector.cleanDB();
		Log.clearLogs();
		RedisConnector.cleanKeys(origins);

		if (origins.length == 0) {// default producer
			origins = new String[1];
			origins[0] = ".";// root properties
		}
		Properties streamsConfiguration = new Properties();
		String[] inputTopics = null;
		String outputTopic = null;
		try (InputStream props = Resources.getResource("setup/" + origins[0] + "/" + "streaming.props").openStream()) {
			streamsConfiguration.load(props);
		}

		streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "PlatformTest");
//		streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
//		streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, "localhost:2181");
		streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");// latest, earliest, none

		// Records should be flushed every 10 seconds. This is less than the default in
		// order to keep this example interactive.
		streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);// in ms

		// Set up serializers and deserializers, which we will use for overriding the
		// default serdes specified above
		// final Serde<String> stringSerde = Serdes.String();
		// final Serde<Long> longSerde = Serdes.Long();

		StoreBuilder storebuilder;// use StoreSupplier
		// TopologyBuilder builder = new TopologyBuilder();
		Topology builder = new Topology();
		StringSerializer stringSerializer = new StringSerializer();
		StringDeserializer stringDeserializer = new StringDeserializer();

		for (int i = 0; i < origins.length; i++) {
			String origin;
			if (origins[i].equals(".")) {
				origin = "default";
			} else {
				origin = origins[i];
			}
			try (InputStream props = Resources.getResource("setup/" + origins[i] + "/" + "streaming.props")
					.openStream()) {
				streamsConfiguration.load(props);
				inputTopics = (streamsConfiguration.getProperty("mainTopic").trim()).split(",");
				outputTopic = streamsConfiguration.getProperty("outputTopic").trim();
			}

			// origin = "default";

			storebuilder = Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("Counts" + origin),
					new Serdes.StringSerde(), new Serdes.StringSerde()).withCachingDisabled();

			// origin = "default";
			builder.addSource("Source" + origin, stringDeserializer, stringDeserializer, inputTopics)
					.addProcessor("Process" + origin, () -> new ProcessorReader(origin, "Counts" + origin),
							"Source" + origin)
					.addStateStore(storebuilder, "Process" + origin)
					.addSink("sink" + origin, outputTopic, stringSerializer, stringSerializer, "Process" + origin);

			/*
			 * storeSup = Stores.create("Counts"+origin) .withKeys(Serdes.String())
			 * .withValues(Serdes.String()) .persistent() .build();
			 * builder.addSource("Source"+origin, stringDeserializer, stringDeserializer,
			 * inputTopics) .addProcessor("Process"+origin, () -> new
			 * ProcessorReader(origin, "Counts"+origin), "Source"+origin)
			 * .addStateStore(storeSup, "Process"+origin) .addSink("sink"+origin,
			 * outputTopic, stringSerializer, stringSerializer, "Process"+origin);
			 */
		}

		KafkaStreams streams = new KafkaStreams(builder, streamsConfiguration);

		if (System.getProperty("os.name").toLowerCase().contains("windows")) {

			// If Kafka is running, then we cannot delete the file (other process is using it)
			try (AdminClient client = KafkaAdminClient.create(streamsConfiguration)) {
				ListTopicsResult topics = client.listTopics();
				
			} catch (Exception ex) {
				// Kafka is not running, clean the files
				cleanKafkaLogsForWindows(outputTopic, inputTopics, origins[0]);
			}
		} else {
			streams.cleanUp();
		}

		streams.start();

		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

		// Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
		/*
		 * Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
		 * 
		 * @Override public void run() { streams.close(); } }));
		 */

		/*
		 * try { Thread.sleep(10000); } catch (InterruptedException e) {
		 * e.printStackTrace(); } streams.close();
		 */
	}
	
	private void cleanKafkaLogsForWindows(String outputTopic, String[] inputTopics, String origin) {
		try {
			// streams.cleanUp() on windows is not working, so I figured out a replacement
			// for it.
			String kafka_streams_path = "C:\\tmp\\kafka-streams\\test\\0_0\\";
			File kafka_streams_log_file = new File(kafka_streams_path);
			if (kafka_streams_log_file.exists() && kafka_streams_log_file.isDirectory())
				FileUtils.cleanDirectory(kafka_streams_log_file);

			String kafka_logs_1 = outputTopic;
			List<String> kafka_logs_inputs = new ArrayList<String>();
			for (String topic_input : inputTopics) {
				kafka_logs_inputs.add(topic_input);
			}
			String kafka_logs_2 = "test-Counts" + origin + "-changelog";

			File kafka_logs_folder = new File("C:\\tmp\\kafka-logs\\");
			File[] files = kafka_logs_folder.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(final File dir, final String name) {
					for (String topic_input : kafka_logs_inputs) {
						if (name.contains(topic_input))
							return name.contains(topic_input);
					}

					return name.contains(kafka_logs_1) || name.contains(kafka_logs_2);
				}
			});
			for (File file : files) {
				FileUtils.forceDelete(file);
			}

		} catch (Exception ex2) {
			System.out.println("The log files of Kafka cannot be cleaned because Kafka is running!");
			ex2.printStackTrace();
		}
	}

}