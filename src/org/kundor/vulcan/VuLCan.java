package org.kundor.vulcan;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.ToggleButton;

public class VuLCan extends Activity {
	static final String TAG = "VuLCan";
	static final int DEFAULT_BUFFER_SIZE=128;
	static final int SEND_MSG = 0;
//	static final int SEND_IGNORE = 1;
	static final int DIALOG_NOCONNECT = 0;
	static final int DIALOG_NOFILE = 1;
	VerticalSeekBar ProgressBar;
    VerticalSeekBar VlcVolBar;
    VerticalSeekBar SysVolBar;
    TextView mProgressText;
    ToggleButton MuteButton;
    volatile boolean bTracking = false;
	volatile int progress;
	int movlength;
	volatile int vlcvolume;
	Handler mTcpHandler;
	
	final Runnable mUpdateProgress = new Runnable() {
        public void run() {
        	if (!bTracking)
        		ProgressBar.setProgress(progress);
        }
    };
    
    final Runnable mSetMax = new Runnable() {
        public final void run() {
            ProgressBar.setMax(movlength);
        }
    };

    final Runnable mSetVolume = new Runnable() {
        public final void run() {
            VlcVolBar.setProgress(vlcvolume);
        }
    };
	
    private final class TCPThreader extends HandlerThread {
        TCPThreader() {
			super("tcpThread");
		}

		private Socket VLCsock;
        private PrintWriter out;
        private InputStreamReader in;
        private static final int UNCONNECTED = 0;  // poor man's enum
        private static final int NOFILE = 1;
        private static final int READY = 2;
        private int constate = UNCONNECTED;
        private boolean bStarted = false;
        
        Handler getHandler() {
        	return new Handler(getLooper()) {
                public void handleMessage(Message msg) {
        			Log.d(TAG, "Handling message " + (String)msg.obj);
        			if (constate == READY)
        				SendMessage((String)msg.obj);
                }
        	};
        }
        
        final Runnable mConnect = new Runnable() {
        	public void run() {
        		Log.d(TAG, "Attempting connect");
        		try {
        			InetAddress VLCip = InetAddress.getByAddress(new byte[]{10, 0, 100, 3});
        			VLCsock = new Socket(VLCip, 4212);
        			VLCsock.setTcpNoDelay(true);
        			out = new PrintWriter(VLCsock.getOutputStream(), true);
        			in = new InputStreamReader(VLCsock.getInputStream());
            		setState(NOFILE);
            		String intro = GetMessage();
        			Log.i(TAG, intro);
//        			if (!intro.equals("VLC media player 1.1.5 The Luggage")) {
//        				return;
//        			}
        		} catch (IOException e) {
        			panicDisconnect();
        			return;
        		}
        		Log.d(TAG, "Posting initialize");
                mTcpHandler.post(mInitialize);
        	}        	
        };
        
        final Runnable mInitialize = new Runnable() {
        	public void run() {
        		Log.d(TAG, "Attempting init");
        		if (constate != NOFILE) {
        			Log.e(TAG, "Initializing when not in NOFILE");
        		}
        		String response = null;
        		try {
        			response = SendGet("get_length");
        			movlength = Integer.parseInt(response);
        			response = SendGet("volume");
        			vlcvolume = Integer.parseInt(response);
        		} catch (IOException e) {
        			panicDisconnect();
        			return;
        		} catch (NumberFormatException e) {
        			Log.e(TAG, "String " + response + "can't be parsed");
            		runOnUiThread(new Runnable() {
            			public void run() { showDialog(DIALOG_NOFILE); }
            		});
        			return;
        		}
    			runOnUiThread(mSetMax);  // synchronized SendGet forces memory sync.  Hopefully UiThread pulls movlength from main memory
    			runOnUiThread(mSetVolume);
    			setState(READY);
        	}
        };
        
        final Runnable mGetVolume = new Runnable() {
        	public void run() {
        		if (constate == UNCONNECTED) return;
        		String response = null;
        		try {
        			response = SendGet("volume");
        			vlcvolume = Integer.parseInt(response);
        		} catch (IOException e) {
        			panicDisconnect();
        			return;
        		} catch (NumberFormatException e) {
        			Log.e(TAG, "String " + response + "can't be parsed");
        			setState(NOFILE);
            		runOnUiThread(new Runnable() {
            			public void run() { showDialog(DIALOG_NOFILE); }
            		});
        			return;
        		}
    			runOnUiThread(mSetVolume);
        	}
        };
        
        final Runnable mGetProgress = new Runnable() {
        	public void run() {
        		if (!bStarted || constate != READY)
        			return;
        		String response = null;
				if (!bTracking) {
	        		Log.d(TAG, "Getting progress");
	        		try {
	        			response = SendGet("get_time");
						progress = Integer.parseInt(response);
					} catch (NumberFormatException e) {
						Log.e(TAG, "String " + response + "can't be parsed");
						setState(NOFILE);
			    		runOnUiThread(new Runnable() {
			    			public void run() { showDialog(DIALOG_NOFILE); }
			    		});
						return;
					} catch (IOException e) {
						panicDisconnect();
						return;
					}
					runOnUiThread(mUpdateProgress);
//					Log.v(TAG, "Progress: " + progress);
				}
				Log.d(TAG, "Posting next get-progress");
				mTcpHandler.postDelayed(this, 1000);
        	}
        };
        
        private void panicDisconnect() {
			setState(UNCONNECTED);
			try {
				in.close();
				out.close();
				VLCsock.close();
			} catch (Exception e) {
				// Ignore it.
			}
    		runOnUiThread(new Runnable() {
    			public void run() { showDialog(DIALOG_NOCONNECT); }
    		});
    		Log.w(TAG, "Problem connecting to VLC; dialog shown");
        }
        
        private void setState(int state) {
        	constate = state;
        	if (bStarted && constate == READY) {
            	Log.d(TAG, "Posting get-progress");
                mTcpHandler.post(mGetProgress);
        	} else {
        		mTcpHandler.removeCallbacks(mGetProgress);
        	}
        }
        
        void setStarted(boolean started) {
        	bStarted = started;
        	if (bStarted && constate == READY) {
            	Log.d(TAG, "Posting get-progress");
                mTcpHandler.post(mGetProgress);
        	} else {
        		mTcpHandler.removeCallbacks(mGetProgress);
        	}
        }
       
    	synchronized void SendMessage(String msg) {
    		try {
				if (in.ready()) { // Bytes are sitting on input - why?!
					panicDisconnect();
				} else if (constate != UNCONNECTED) {
					out.println(msg);
					if (out.checkError())
						panicDisconnect();
					sleep(50);
				}
			} catch (IOException e) {
				panicDisconnect();
			} catch (InterruptedException e) {
				Log.w(TAG, "Interrupted!");
			}
    	}
    	
    	synchronized String GetMessage() throws IOException {
    		if (constate == UNCONNECTED)
    			throw new IOException();
    		char[] buf = new char[DEFAULT_BUFFER_SIZE];
    		int read = 0, ret = 0;
			while (read == 0 || buf[read - 1] != '\n') {
				ret = in.read(buf, read, DEFAULT_BUFFER_SIZE - read);
				if (ret < 0) // EOF.  If we've read some bytes, return those.  Otherwise, empty.
					break;
				read += ret;
			}
    		return new String(buf, 0, read).trim();
    	}
    	
    	synchronized String SendGet(String msg) throws IOException {
    		//Synchronized, so no other invocations can interleave
    		SendMessage(msg);
    		return GetMessage();
    	}
    }
    private final TCPThreader tcpThread = new TCPThreader();
    
    public void executeTag(View v) {
    	if (mTcpHandler != null)
    		mTcpHandler.obtainMessage(SEND_MSG, v.getTag()).sendToTarget();
    }
    
    public void VLCVolumeAdjust(View v) {
    	if (mTcpHandler == null) return;
    	mTcpHandler.obtainMessage(SEND_MSG, v.getTag()).sendToTarget();
    	mTcpHandler.post(tcpThread.mGetVolume);
    	MuteButton.setChecked(false);
    }
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ProgressBar = (VerticalSeekBar)findViewById(R.id.ProgressSeekBar);
        mProgressText = (TextView)findViewById(R.id.progress);
        VlcVolBar = (VerticalSeekBar)findViewById(R.id.VLCVolSeekBar);
        SysVolBar = (VerticalSeekBar)findViewById(R.id.SysVolSeekBar);
        MuteButton = (ToggleButton)findViewById(R.id.mute);
        
        VlcVolBar.setMax(1024);
        SysVolBar.setMax(100);
        ProgressBar.setOnSeekBarChangeListener(new VerticalSeekBar.OnSeekBarChangeListener() {
            public void onStopTrackingTouch(VerticalSeekBar seekBar) {
            	bTracking = false;
            	if (mTcpHandler != null)
            		mTcpHandler.obtainMessage(SEND_MSG, "seek " + seekBar.getProgress()).sendToTarget();
            }

            public void onStartTrackingTouch(VerticalSeekBar seekBar) {
            	bTracking = true;
            }

            public void onProgressChanged(VerticalSeekBar seekBar, int progress, boolean fromUser) {
            	if (fromUser && !bTracking && mTcpHandler != null)
            		mTcpHandler.obtainMessage(SEND_MSG, "seek " + progress).sendToTarget();
            	if (fromUser)
            		mProgressText.setText(progress + "/" + movlength);
            }
    	});
        tcpThread.start();
		mTcpHandler = tcpThread.getHandler();
		Log.d(TAG, "Posting connect");
        mTcpHandler.post(tcpThread.mConnect);
    }
    
    protected void onStart() {
    	super.onStart();
    	tcpThread.setStarted(true);
    }
    
    protected void onStop() {
    	tcpThread.setStarted(false);
    	super.onStop();
    }
    
    protected Dialog onCreateDialog(int id) {
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	switch(id) {
    	case DIALOG_NOCONNECT:
    		builder.setMessage("Couldn't connect to VLC!")
    			   .setCancelable(false)
    			   .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
    				   public void onClick(DialogInterface dialog, int id) {
    					   Log.d(TAG, "Posting connect");
    					   mTcpHandler.post(tcpThread.mConnect);
    					   dialog.dismiss();
    				   }
    			   })
    			   .setNegativeButton("Quit", new DialogInterface.OnClickListener() {
    				   public void onClick(DialogInterface dialog, int id) {
    					   VuLCan.this.finish();
    				   }
    			   });
    		break;
    	case DIALOG_NOFILE:
    		builder.setMessage("No file loaded in VLC.")
			   .setCancelable(false)
			   .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
				   public void onClick(DialogInterface dialog, int id) {
					   Log.d(TAG, "Posting Initialize");
					   mTcpHandler.post(tcpThread.mInitialize);
					   dialog.dismiss();
				   }
			   })
			   .setNegativeButton("Quit", new DialogInterface.OnClickListener() {
				   public void onClick(DialogInterface dialog, int id) {
					   VuLCan.this.finish();
				   }
			   });
    		break;
    	default:
    		return null;
    	}
    	return builder.create();
    }
    
    public void onDestroy() {
    	mTcpHandler.getLooper().quit();
    	super.onDestroy();
    }
}