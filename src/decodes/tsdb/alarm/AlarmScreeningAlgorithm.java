/**
 * $Id$
 * 
 * $Log$
 */
package decodes.tsdb.alarm;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import ilex.var.NamedVariableList;
import ilex.util.Logger;
import ilex.util.PropertiesUtil;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.IntervalIncrement;
import decodes.tsdb.VarFlags;
import decodes.tsdb.alarm.mail.MailerException;
import decodes.tsdb.algo.AWAlgoType;
import decodes.util.PropertySpec;
import decodes.hdb.HdbFlags;
import decodes.sql.DbKey;
import decodes.tsdb.BadTimeSeriesException;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.ParmRef;
import ilex.var.TimedVariable;
import opendcs.dai.AlarmDAI;
import opendcs.dai.TimeSeriesDAI;
import decodes.tsdb.TimeSeriesIdentifier;

//AW:IMPORTS
// Place an import statements you need here.
//AW:IMPORTS_END

//AW:JAVADOC
/**
Look for alarm screening records in the database and apply to input parameter.
 */
//AW:JAVADOC_END
public class AlarmScreeningAlgorithm
	extends decodes.tsdb.algo.AW_AlgorithmBase
{
//AW:INPUTS
	public double input;	//AW:TYPECODE=i
	String _inputNames[] = { "input" };
//AW:INPUTS_END

//AW:LOCALVARS
	// Will be set to true if input and output refer to the same time series.
	boolean _inputIsOutput = false;
	boolean _noOutput = false;
	private Date earliestTrigger = null, latestTrigger = null;
	private ArrayList<AlarmScreening> screenings = new ArrayList<AlarmScreening>();
	ParmRef inputParm = null;
	private AlarmScreening tScreening = null;
	private AlarmLimitSet tLimitSet = null;
	
	// Enter any local class variables needed by the algorithm.
	PropertySpec algoPropSpecs[] =
	{
		new PropertySpec("noOverwrite", PropertySpec.BOOLEAN, "(default=false) "
			+ "Set to true to disable overwriting of output parameter."),
		new PropertySpec("setInputFlags", PropertySpec.BOOLEAN, "(default=false) "
			+ "Set to true to set quality flags on the input parameter."),
		new PropertySpec("noOutputOnReject", PropertySpec.BOOLEAN, "(default=false) "
			+ "If true and the value is REJECTED, then do not write output param at all. "),
		
		new PropertySpec("mail.smtp.auth", PropertySpec.BOOLEAN, "(default=false) "
			+ "If true then authenticate when connecting to mail server."),
		new PropertySpec("mail.smtp.starttls.enable", PropertySpec.BOOLEAN, "(default=false) "
			+ "Use TLS for a secure connection to the mail server."),
		new PropertySpec("mail.smtp.host", PropertySpec.HOSTNAME, "(required) "
			+ "Host name or IP address of the mail server."),
		new PropertySpec("mail.smtp.port", PropertySpec.INT, "(default=587) "
			+ "Port number for connecting to the mail server"),
		new PropertySpec("smtp.username", PropertySpec.STRING, 
			"User name for authenticating to the mail server"),
		new PropertySpec("smtp.password", PropertySpec.STRING, 
			"Password for authenticating to the mail server"),
		new PropertySpec("fromAddr", PropertySpec.STRING, 
			"Email address for the 'from' field of the header"),
		new PropertySpec("fromName", PropertySpec.STRING, 
			"Name for the 'from' field of the header"),
	
		new PropertySpec("resendSeconds", PropertySpec.INT, "(default=86400) "
			+ "Resend email for existing alarms if they remain asserted this long. "
			+ "(-1 to disable resend)"),
		new PropertySpec("notifyMaxAgeDays", PropertySpec.INT, "(default=30) "
			+ "Do not send email notifications for data older than this.")
	};
	
	@Override
	protected PropertySpec[] getAlgoPropertySpecs()
	{
		return algoPropSpecs;
	}
	
	private void getAlarmScreenings(TimeSeriesIdentifier inputTsid)
		throws DbCompException
	{
		AlarmDAI alarmDAO = tsdb.makeAlarmDAO();
		
		try
		{
			screenings = alarmDAO.getScreenings(inputTsid.getSite().getId(), inputTsid.getDataTypeId());
			if (screenings == null)
				throw new DbCompException("Invalid TSID for screening '" + inputTsid.getUniqueString() + "'");
			
			// If there are no site-specific screenings, OR if the earliest input is before the 
			// site specific screenings, look for a generic one with siteId==nullkey.
			if (screenings.size() == 0 
			 || (screenings.get(0).getStartDateTime() != null 
			       && earliestTrigger.before(screenings.get(0).getStartDateTime())))
			{
				boolean noSiteScreenings = screenings.size() == 0;
				
				// Need to look for generic screening
				ArrayList<AlarmScreening> genScr = 
					alarmDAO.getScreenings(DbKey.NullKey, inputTsid.getDataTypeId());
				if (genScr != null && genScr.size() > 0)
				{
					for(int idx = 0; 
						idx < genScr.size() 
						&& noSiteScreenings
						   || (genScr.get(idx).getStartDateTime() == null
						       || genScr.get(idx).getStartDateTime().before(earliestTrigger))
							; idx++)
					{
						screenings.add(idx, genScr.get(idx));
					}
				}
			}
debug1("There are " + screenings.size() + " screenings: ");
for(AlarmScreening as : screenings) debug1("   start = " + as.getStartDateTime());
		}
		catch (Exception ex)
		{
			String msg = "Error reading alarm screenings: " + ex;
			warning(msg);
			PrintStream logout = Logger.instance().getLogOutput();
			if (logout != null)
				ex.printStackTrace(logout);
			throw new DbCompException(msg);
		}
		finally
		{
			alarmDAO.close();
		}
		if (screenings.size() == 0)
			throw new DbCompException("No applicable screenings for TSID '" 
				+ inputTsid.getUniqueString() + "'");
	}

	/**
	 * Find the appropriate screening (by start date) and limit set (by season) for
	 * time t. Set the instance variables tScreening and tLimitSet;
	 * @param t the time
	 * @return true if a screening and limit set was found. False if not.
	 */
	private boolean initScreeningAndLimitSet(Date t)
	{
		tScreening = null;
		tLimitSet = null;
		
		// Find the latest screening with start <= t.
		for(AlarmScreening as : screenings)
		{
			if (as.getStartDateTime() != null && as.getStartDateTime().after(t))
				break;
			tScreening = as;
		}
		
		if (tScreening == null)
		{
			info("No applicable screening for '" + inputParm.tsid.getUniqueString() 
				+ "' at time " + debugSdf.format(t));
			return false;
		}
		
		// Now find the limit set within the screening.
		for(AlarmLimitSet als : tScreening.getLimitSets())
		{
			if (als.getSeason() == null) // This is the default (non-seasonal) limit set?
				tLimitSet = als;
			else if (als.getSeason().isInSeason(t))
			{
				tLimitSet = als;
				break;
			}
		}
		if (tLimitSet == null)
		{
			info("Screening '" + tScreening.getScreeningName() + "' with id=" + tScreening.getScreeningId()
				+ " does not have a limit set for date/time=" + debugSdf.format(t));
			return false;
		}
		
		return true;
	}



//AW:LOCALVARS_END

//AW:OUTPUTS
	public NamedVariable output = new NamedVariable("output", 0);
	String _outputNames[] = { "output" };
//AW:OUTPUTS_END

//AW:PROPERTIES
	public boolean noOutputOnReject = false;
	public boolean noOverwrite = false;
	public boolean setInputFlags = false;
	String _propertyNames[] = { "noOutputOnReject", "noOverwrite", "setInputFlags" };
//AW:PROPERTIES_END

	// Allow javac to generate a no-args constructor.
	
	

	/**
	 * Algorithm-specific initialization provided by the subclass.
	 */
	protected void initAWAlgorithm( )
		throws DbCompException
	{
//AW:INIT
		_awAlgoType = AWAlgoType.TIME_SLICE;
//AW:INIT_END

//AW:USERINIT
		// Code here will be run once, after the algorithm object is created.
//AW:USERINIT_END
	}
	
	/**
	 * This method is called once before iterating all time slices.
	 */
	protected void beforeTimeSlices()
		throws DbCompException
	{
//AW:BEFORE_TIMESLICES

		// Find the Screening record
		inputParm = getParmRef("input");
		TimeSeriesIdentifier inputTsid = inputParm.timeSeries.getTimeSeriesIdentifier();
		if (inputTsid == null)
			throw new DbCompException("No input time-series identifier!");
		
		// Determine if input and output refer to the same time series.
		ParmRef outputParm = getParmRef("output");
		TimeSeriesIdentifier outputTsid = outputParm.timeSeries.getTimeSeriesIdentifier();
		if (outputTsid == null)
		{
			TimeSeriesDAI timeSeriesDAO = tsdb.makeTimeSeriesDAO();
			try
			{
				timeSeriesDAO.fillTimeSeriesMetadata(outputParm.timeSeries);
			}
			catch (Exception ex)
			{
				throw new DbCompException("No output tsid and can't retrieve: " + ex);
			}
			finally
			{
				timeSeriesDAO.close();
			}
			outputTsid = outputParm.timeSeries.getTimeSeriesIdentifier();
			if (outputTsid == null)
			{
				// Allow no output
				_noOutput = true;
				info("No output time-series -- will generate alarms "
					+ (setInputFlags ? "and set input flags." : "only."));
			}
		}

		_inputIsOutput = inputTsid.getKey() == outputTsid.getKey();
		info("_inputIsOutput=" + _inputIsOutput);

		getAlarmScreenings(inputTsid); // will throw DbCompException if it fails.
		
		// Find the first and last values in the time series that are triggers.
		// Then prefetch data needed for ROC and stuck sensor alarms.
		Date fetchFrom = null;
		earliestTrigger = latestTrigger = null;
		for(int idx = 0; idx < inputParm.timeSeries.size(); idx++)
		{
			TimedVariable tv = inputParm.timeSeries.sampleAt(idx);
			if (VarFlags.wasAdded(tv))
			{
				if (earliestTrigger == null)
					earliestTrigger = tv.getTime();
				latestTrigger = tv.getTime();
			}
			else
				continue;
			
			if (!initScreeningAndLimitSet(tv.getTime()))
				continue;
			
			if (tLimitSet.getStuckDuration() != null)
			{
				IntervalIncrement stuckDurII = IntervalIncrement.parse(tLimitSet.getStuckDuration());
				if (stuckDurII != null)
				{
					aggCal.setTime(tv.getTime());
					int count = stuckDurII.getCount();
					if (count > 0)
						count = -count;
					aggCal.add(stuckDurII.getCalConstant(), count);
					Date t = aggCal.getTime();
					if (fetchFrom == null || t.before(fetchFrom))
						fetchFrom = t;
				}
			}
			if (tLimitSet.getRocInterval() != null)
			{
				IntervalIncrement rocII = IntervalIncrement.parse(tLimitSet.getStuckDuration());
				if (rocII != null)
				{
					aggCal.setTime(tv.getTime());
					int count = rocII.getCount();
					if (count > 0)
						count = -count;
					aggCal.add(rocII.getCalConstant(), count);
					Date t = aggCal.getTime();
					if (fetchFrom == null || t.before(fetchFrom))
						fetchFrom = t;
				}
			}
		}
		if (earliestTrigger == null)
			throw new DbCompException("triggered for input tsid '" + inputTsid.getUniqueString()
			+ "' but no input trigger values.");
		
		// If we need historical data, fetch it, but don't overwrite existing data in the TS object.
		if (fetchFrom != null)
		{
			try
			{
				tsdb.fillTimeSeries(inputParm.timeSeries, fetchFrom, latestTrigger, true, false, false);
			}
			catch (Exception ex)
			{
				warning("Error filling time series '" + inputParm.tsid.getUniqueString()
					+ "' for time range " + debugSdf.format(fetchFrom) + " ... " 
					+ debugSdf.format(latestTrigger) + ": " + ex);
			}
		}
		
		// Note: It is up to the user to make sure input and output are in the correct units.
		
//AW:BEFORE_TIMESLICES_END
	}

	
	/**
	 * Do the algorithm for a single time slice.
	 * AW will fill in user-supplied code here.
	 * Base class will set inputs prior to calling this method.
	 * User code should call one of the setOutput methods for a time-slice
	 * output variable.
	 *
	 * @throws DbCompException (or subclass thereof) if execution of this
	 *        algorithm is to be aborted.
	 */
	protected void doAWTimeSlice()
		throws DbCompException
	{
//AW:TIMESLICE
		Date t = this._timeSliceBaseTime;
		
		if (!initScreeningAndLimitSet(t))
			return;
		
		debug1("Executing screening ' " + tScreening.getScreeningName() + "' with id=" 
			+ tScreening.getScreeningId() + " with limit set season="
			+ (tLimitSet.getSeason() == null ? "(default)" : tLimitSet.getSeason().getAbbr())
			+ " at time " + debugSdf.format(t) + " with value " + input);
		
		// NOTE: Alarm flag definitions are the same for HDB and OpenTSDB, so we use the HdbFlags
		// definitions here. After accumulating flag values, convert them to CWMS if necessary.
		int flags = HdbFlags.SCREENED;
		
		double UL = AlarmLimitSet.UNASSIGNED_LIMIT;
		
		// Check the absolute value limits
		if (tLimitSet.getRejectHigh() != UL && input >= tLimitSet.getRejectHigh())
			flags |= HdbFlags.SCR_VALUE_REJECT_HIGH;
		else if (tLimitSet.getCriticalHigh() != UL && input >= tLimitSet.getCriticalHigh())
			flags |= HdbFlags.SCR_VALUE_CRITICAL_HIGH;
		else if (tLimitSet.getWarningHigh() != UL && input >= tLimitSet.getWarningHigh())
			flags |= HdbFlags.SCR_VALUE_WARNING_HIGH;
		else if (tLimitSet.getRejectLow() != UL && input <= tLimitSet.getRejectLow())
			flags |= HdbFlags.SCR_VALUE_REJECT_LOW;
		else if (tLimitSet.getCriticalLow() != UL && input <= tLimitSet.getCriticalLow())
			flags |= HdbFlags.SCR_VALUE_CRITICAL_LOW;
		else if (tLimitSet.getWarningLow() != UL && input <= tLimitSet.getWarningLow())
			flags |= HdbFlags.SCR_VALUE_WARNING_LOW;
		
		double delta = 0.0;
		if (tLimitSet.getRocInterval() != null)
		{
			// Use the interval to determine an actual time period, then fetch time series
			// data if necessary and compute a delta.
			IntervalIncrement rocII = IntervalIncrement.parse(tLimitSet.getStuckDuration());
			TimedVariable startOfPeriod = null;
			if (rocII != null)
			{
				aggCal.setTime(t);
				int count = rocII.getCount();
				if (count > 0)
					count = -count;
				aggCal.add(rocII.getCalConstant(), count);
				Date from = aggCal.getTime();

				startOfPeriod = inputParm.timeSeries.findInterp(from.getTime()/1000L);
				if (startOfPeriod != null)
				{
					try { delta = input - startOfPeriod.getDoubleValue(); }
					catch(Exception ex)
					{
						warning("Cannot do ROC check because startOfPeriod had non-numeric value: " 
							+ startOfPeriod + " - " + ex);
						startOfPeriod = null;
					}
				}
				
				if (startOfPeriod != null)
				{
					// Check the ROC limits
					if (tLimitSet.getRejectRocHigh() != UL && delta >= tLimitSet.getRejectRocHigh())
						flags |= HdbFlags.SCR_ROC_REJECT_HIGH;
					else if (tLimitSet.getCriticalRocHigh() != UL && delta >= tLimitSet.getCriticalRocHigh())
						flags |= HdbFlags.SCR_ROC_CRITICAL_HIGH;
					else if (tLimitSet.getWarningRocHigh() != UL && delta >= tLimitSet.getWarningRocHigh())
						flags |= HdbFlags.SCR_ROC_WARNING_HIGH;
					else if (tLimitSet.getRejectRocLow() != UL && delta <= tLimitSet.getRejectRocLow())
						flags |= HdbFlags.SCR_ROC_REJECT_LOW;
					else if (tLimitSet.getCriticalRocLow() != UL && delta <= tLimitSet.getCriticalRocLow())
						flags |= HdbFlags.SCR_ROC_CRITICAL_LOW;
					else if (tLimitSet.getWarningRocLow() != UL && delta <= tLimitSet.getWarningRocLow())
						flags |= HdbFlags.SCR_ROC_WARNING_LOW;
				}
			}
		}

		double variance = 0.0;
		if (tLimitSet.getStuckDuration() != null)
		{
			IntervalIncrement stuckDurII = IntervalIncrement.parse(tLimitSet.getStuckDuration());
			if (stuckDurII != null)
			{
				aggCal.setTime(t);
				int count = stuckDurII.getCount();
				if (count > 0)
					count = -count;
				aggCal.add(stuckDurII.getCalConstant(), count);
				Date from = aggCal.getTime();
				int n = 0;
				double lowv = 0.0, highv = 0.0;
				for(int idx = inputParm.timeSeries.findNextIdx(from); 
					idx != -1 && idx < inputParm.timeSeries.size(); idx++)
				{
					TimedVariable tv = inputParm.timeSeries.sampleAt(idx);
					if (tv.getTime().after(t))
						break;
					try
					{
						double v = tv.getDoubleValue();
						if (n++ == 0)
							lowv = highv = v;
						else
						{
							if (v < lowv)
								lowv = v;
							if (v > highv)
								highv = v;
						}
					}
					catch(Exception ex) {}
				}
				if (n > 1 && (variance = (highv - lowv)) <= tLimitSet.getStuckTolerance())
				{
					flags |= HdbFlags.SCR_STUCK_SENSOR_DETECTED;
				}
			}
		}
		
		checkAlarms(t, input, delta, variance, flags);
		
		
		// If one of:
		//   - property saying to set the input flags
		//   - The input and output are the same time series
		if (setInputFlags || _inputIsOutput)
		{
			setInputFlagBits("input", flags, HdbFlags.SCREENING_MASK);
		}
		// Note: if (_noOutput && ! either of the above) then the comp doesn't
		// save the flags anywhere. The only purpose would be to generate alarms.
		
		// If there is an output that is different from the input
		if (!_noOutput && !_inputIsOutput)
		{
			if (!(noOutputOnReject && HdbFlags.isRejected(flags)))
			{
				if (noOverwrite)
					flags |= VarFlags.NO_OVERWRITE; // 0x0c
				output.setFlags(flags);
				setOutput(output, input);
			}
		}
		
//AW:TIMESLICE_END
	}

	private void checkAlarms(Date t, double value, double delta, double variance, int flags)
	{
		//  Hand the alarm assertion (or de-assertion) off to the singleton AlarmManager (TBD)
		//  AlarmManager maintains a queue of assertions.
		AlarmManager.instance(tsdb).checkAlarms(inputParm.tsid, tLimitSet, tScreening, 
			t, value, delta, variance, flags);
	}

	/**
	 * This method is called once after iterating all time slices.
	 */
	protected void afterTimeSlices()
		throws DbCompException
	{
//AW:AFTER_TIMESLICES
		// This code will be executed once after each group of time slices.
		// For TimeSlice algorithms this is done once after all slices.
		// For Aggregating algorithms, this is done after each aggregate
		// period.
//AW:AFTER_TIMESLICES_END
	}

	/**
	 * Required method returns a list of all input time series names.
	 */
	public String[] getInputNames()
	{
		return _inputNames;
	}

	/**
	 * Required method returns a list of all output time series names.
	 */
	public String[] getOutputNames()
	{
		return _outputNames;
	}

	/**
	 * Required method returns a list of properties that have meaning to
	 * this algorithm.
	 */
	public String[] getPropertyNames()
	{
		return _propertyNames;
	}
	
}