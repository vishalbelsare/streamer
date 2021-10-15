package cea.util.metrics;

import java.util.Vector;

import cea.streamer.core.TimeRecord;

public class MeanSquareErrorMetric extends RegressionMetric {
	/**
	 * This method is used to unify the names of all the Mean Square Error metrics
	 * 
	 * @return unified name precised
	 */
	@Override
	public String getName() {
		return "mean_square_error";
	}
	
	/**
	 * This method is for evaluating the MSE metric for the Regression problems
	 * It considers target/output with just one value.
	 * @return Vector<Double> which contains the calculation of the Mean Square Error
	 */
	@Override
	public Vector<Double> evaluate(Vector<TimeRecord> records, String id) {
		double result = -1;
		double sum_sqaure_errors = 0;
		for(TimeRecord record: records) {
			if (record.getTarget().isEmpty() || record.getOutput().isEmpty())
				continue;

			sum_sqaure_errors += Math.pow((Double.parseDouble(record.getTarget().get(0))- Double.parseDouble(record.getOutput().get(0))), 2);

		}
		result = safeDivison(sum_sqaure_errors,records.size());
		result = this.roundAvoid(result, 3);
		
		Vector<Double> ret = new Vector<Double>();
		ret.add(result);
		return ret;
	}
}
