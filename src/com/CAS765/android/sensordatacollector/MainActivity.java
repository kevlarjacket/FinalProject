/*
 *  Copyright (c) 2014, Rong Zheng <rzheng@mcmaster.ca>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.CAS765.android.sensordatacollector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.location.*;

public class MainActivity extends Activity implements SensorEventListener {
	// If the values are set to 0 then we will sample as fast as we can
	// all in Hz
	private final int MAX_SAMPLE = 100;
	private final int DEFAULT_SAMPLING_RATE = 50;

	private Button mStartButton, mResetButton, mDelayTestButton;
	private TextView mStepCount, mHeadingOffset;
	//private TextView mStdDev, mMaxAuto,mCurState,mAccelMag,mTOpt;
	private SensorManager mSensorManager;
	private Sensor mAccelerometer, mGyro, mCompass;
	private boolean isStarted = false;
	private OutputStreamWriter mAccOutWriter;
	private FileOutputStream mAccOut;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mStepCount = (TextView) findViewById(R.id.stepDisplay);
		mHeadingOffset = (TextView) findViewById(R.id.headingDisplay);

		// Check the availability of the sensors. Disable the corresponding
		// views if the sensor is not available
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		
		mCompass = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
		
		// get the accelerometer sensor
		if ((mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)) == null) {
			new AlertDialog.Builder(this)
			.setTitle("No Accelerometer found")
			.setMessage("Without an accelerometer, this application will be unable to " +
					"count your steps. Closing the application")
					.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) { 
							finish();
							System.exit(0);
						}
					})
					.show();
		}

		mStartButton = (Button) findViewById(R.id.start_button);
		mStartButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (!isStarted) {
					mStartButton.setText("Stop");
					startSensors();
					isStarted = true;
				} else {
					mStartButton.setText("Start");
					stopAll();
					isStarted = false;
				}
			}
		});

		mResetButton = (Button) findViewById(R.id.reset_button);
		mResetButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				resetAll();
			}
		});
		
		mDelayTestButton = (Button) findViewById(R.id.delayTest);
		
		mDelayTestButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				// KB I'm changing this button to get the nearest GPS coordinates. 
				
				LocationManager service = (LocationManager) getSystemService(LOCATION_SERVICE);
				boolean enabled = service
				  .isProviderEnabled(LocationManager.GPS_PROVIDER);

				// check if enabled and if not send user to the GSP settings
				if (!enabled) {
					new AlertDialog.Builder(MainActivity.this)
					.setTitle("GPS not enabled")
					.setMessage("To establish your starting location, you must enable GPS.")
					.setNegativeButton("Dismiss",new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog,int id) {
							// if this button is clicked, just close
							// the dialog box and do nothing
							dialog.cancel();
						}
					})
					.setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) { 
							// Open Settings application
							Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
							startActivity(intent);
						}
					})
					.show();
				} else {
					// A corrosponds to A on the map.  it is the reference location.
					LatLng A = new LatLng(43.2584365, -79.9213903);
					LatLng Start = getLocation();
					// points from the map, can be used for testing. 
					//	A(43.2584365, -79.9213903) 
					//	B(43.2584002, -79.9204924) 
					//	C(43.2591504, -79.9205123) 
					//	D(43.2591378, -79.9214089)
					// Start = new LatLng(43.2591504, -79.9205123);
					
					double RelLat = Start.lat - A.lat;
					double RelLng = Start.lng - A.lng;
					double SlopeLat = 0.000000977;
					double SlopeLng = 0.000001349;
					double X = RelLat / SlopeLat + 483;
					double Y = RelLng / SlopeLng + 141;
					
					//mStepCount.setText(""+Start.lat + " "+Start.lng);
					mStepCount.setText(""+X + " "+Y);
					
					// TODO: check to ensure coordinates are near ITB
					
				}

			}
		});

		try {
			// get the file handlers for the external storage
			String state = Environment.getExternalStorageState();

			if (Environment.MEDIA_MOUNTED.equals(state)) {
				String dir = getExternalFilesDir(null) + File.separator
						+ System.currentTimeMillis();
				File projDir = new File(dir);
				if (!projDir.exists())
					projDir.mkdirs();

				// KB: the file names below were given the extension ".txt" in
				// order to make them viewable in the file manager on the 
				// device.  
				File myFile = new File(dir + File.separator + "/acc.txt");
				myFile.createNewFile();
				mAccOut = new FileOutputStream(myFile);
				mAccOutWriter = new OutputStreamWriter(mAccOut);

			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}// End onCreate
	
	//	KB
	//	This function gets the GPS coordinates of the device, and returns a LatLng object. 
	public LatLng getLocation()
	{ 
		// Get the location manager
		LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		LatLng GPScoords = new LatLng(location.getLatitude(), location.getLongitude());
		
		Log.d("GPScoords","Lat="+GPScoords.getLat()+" Lng="+GPScoords.getLng());
		
		return GPScoords;
		
		
	}
	
	// KB 
	// This class contains the Latitude and Longitude of a location
	final class LatLng {
	    private final double lat;
	    private final double lng;

	    public LatLng(double first, double second) {
	        this.lat = first;
	        this.lng = second;
	    }

	    public double getLat() {
	        return lat;
	    }

	    public double getLng() {
	        return lng;
	    }
	}
	
	
	@Override
	protected void onPause() {
	    // Unregister the listener on the onPause() event to preserve battery life;
	    super.onPause();
	    mSensorManager.unregisterListener(this);
	}

	@Override
	protected void onResume() {
	    super.onResume();
	    mSensorManager.registerListener(this, mCompass, SensorManager.SENSOR_DELAY_NORMAL);
	}

	protected void onStop() {
		if (mAccOut != null) {
			try {
				mAccOutWriter.close();
				mAccOut.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		super.onStop();
	}


	void startSensors() {
		mSensorManager.registerListener(this, mAccelerometer, DEFAULT_SAMPLING_RATE);
	}

	//Upon stopping the sensors, 
	void stopAll() {
		mSensorManager.unregisterListener(this, mAccelerometer);

		try {
			if (mAccOutWriter != null)
				mAccOutWriter.flush();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	void resetAll() {
		if (isStarted) {
			mStartButton.setText("Start");

			stopAll();
			isStarted = false;
		}
//		accelData  = new  LinkedList<Double>();
//		displayInterval=0;
//		steps = 0;						
//		state = INACTIVE;
//		T = 0;
//		Topt = 70;					// Optimal tau value, changed in program
//		Tmin = 40;					// Minimum tau value
//		Tmax = 100; 				// Maximum tau value
//		samplesWalking = 0;	
		
		mStepCount.setText("0");
		mHeadingOffset.setText("0");
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// TODO Auto-generated method stub

	}

//	// --------------------------------------------------------------------
//	// These are the variables needed for our calculations
//	public static final int 
//			INACTIVE = 0, 
//			WALKING = 1,
//			MAXDATA = 200;						//the maximum amount of data points saved
//	
//	private int displayInterval=0;
//	private int steps = 0;						// Total steps taken is global variable
//	private int state = INACTIVE;    			// maybe either 0, for inactive, or 1, for walking
//	
//	//List<Double> v = new LinkedList<Double>();	// Array of maximum auto-correlation values across samples
//	//double v = 0;
//	// Current data input sample 'm'
//	List<Double> accelData  = new  LinkedList<Double>();	// Input acceleration data, assumed in g
//	 	
//	private int T = 0,
//			Topt = 70,					// Optimal tau value, changed in program
//			Tmin = 40,					// Minimum tau value
//			Tmax = 100, 				// Maximum tau value
//			samplesWalking = 0;			// m for simplicity is set for currentsamples;
//	// --------------------------------------------------------------------

	// Handling sensor reading changes
	@Override
	public void onSensorChanged(SensorEvent event) {
		
	    float azimuth = Math.round(event.values[0]);
	    // The other values provided are: 
	    //  float pitch = event.values[1];
	    //  float roll = event.values[2];
	    mHeadingOffset.setText("Azimuth: " + Float.toString(azimuth));
		
//		// all code goes here!
//		displayInterval++;
//		// calculate magnitude of acceleration.
//		double accelX = event.values[0];
//		double accelY = event.values[1];
//		double accelZ = event.values[2];
//
//		
//		
//		double accelMag = Math.sqrt(Math.pow(accelX,2) + Math.pow(accelY,2) + Math.pow(accelZ,2));
//		
//		// save current acceleration value.
//		accelData.add(accelMag);
//		//mStepCount.setText(Double.toString(accelMag));
//	
//		if (accelData.size() > MAXDATA)
//			accelData.remove(0);
//		else
//			return; 		// 
//		
//		List<Double> x = new  LinkedList<Double>();		// Array of normalized auto-correlation values for this sample
//		double numerator, denominator;
//		numerator=0;
//		//Log.d("TMINMAX","TMIN="+Tmin+" TMAX="+Tmax);
//		for (T = Tmin; T <= Tmax; T++)		// Loop through search window
//		{
//			double totalNumerator = 0;
//			double mean1 = mean(0,T),
//					mean2 = mean(T,T);
//			//Log.d("Means", "Mean 1: "+mean1+" Mean 2:"+mean2);
//			for (int k = 0; k<= T-1; k++)	// Calculate numerator sum, mean still needs to be done
//			{
//				numerator = (accelData.get(k) - mean1) * (accelData.get(k+T) - mean2); 	
//				//Log.d("K", "K is "+k);
//				totalNumerator = totalNumerator + numerator;
//			}
//
//			denominator = T * (stdDev(0,T,mean1)) * (stdDev(T,T,mean2));	//  Calculate denominator, stdDev needs to be figured out
//			x.add( totalNumerator / denominator);	// Put values of x in arrayList
//			//Log.d("","T #"+T+" Completed");
//		}
//		double v=0;
//		//Log.d("XSIZE",""+x.size());
//		for (int i = 0; i < x.size(); i++ )	// Find maximum value in x, and set v(m) and Topt accordingly
//		{
//			//Log.d("X", "X is "+x.get(i));
//			if (Math.abs(x.get(i)) >v)
//			{
//			//	Log.d("NEW V","OLD V is "+v+" NEW V is "+x.get(i)); 
//				v =  Math.abs(x.get(i));
//				Topt = i + Tmin;
//			}
//		}
//		//Log.d("V", "V is "+v);
//		Tmin = Topt - 10;				// Set Tmin / Tmax, in paper they used +/-10
//		if (Tmin<=40) {Tmin=40;}
//		Tmax = Topt + 10;
//		if (Tmax>=100) {Tmax=100;}
//			
//		
//		if (stdDev(0,DEFAULT_SAMPLING_RATE+1,mean(0,DEFAULT_SAMPLING_RATE+1)) < 0.01*9.8)		// If standard deviation of current data is <0.01, set state back to IDLE
//		{
//			state = INACTIVE;
//			samplesWalking = 0;			// Also reset samples spent walking
//		}
//		else if (v > 0.7)
//		{
//			state = WALKING;			// If v for current sample is above 0.7, state is now WALKING
//		}
//		
//		//Log.d("STATE","State "+state);
//		if (state == WALKING)			// When in walking state, do this
//		{
//				samplesWalking++;			// Increment samples spent walking
//				if (samplesWalking > (Topt/2))		// 
//				{
//					steps++;
//					samplesWalking = 0;
//					//mStepCount.setText(""+steps);
//				}
//		}	
//		
//		if (displayInterval >=50)
//		{
//			displayInterval=0;
//			mTOpt.setText("T Optimal: "+Topt);
//			mMaxAuto.setText("Max Autocorrelation: "+v);
//			mStdDev.setText("NOTHING");
//			if (state==INACTIVE) mCurState.setText("State=INACTIVE");
//			if (state==WALKING) mCurState.setText("State=WALKING");
//			mAccelMag.setText("NOTHING");
//			
//			
//		}
	}
}
