/******************************************************************************
 *  DroidTV, live TV on Android devices with host USB port and a DVB tuner    *
 *  Copyright (C) 2012  Christian Ulrich <chrulri@gmail.com>                  *
 *                                                                            *
 *  This program is free software: you can redistribute it and/or modify      *
 *  it under the terms of the GNU General Public License as published by      *
 *  the Free Software Foundation, either version 3 of the License, or         *
 *  (at your option) any later version.                                       *
 *                                                                            *
 *  This program is distributed in the hope that it will be useful,           *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of            *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             *
 *  GNU General Public License for more details.                              *
 *                                                                            *
 *  You should have received a copy of the GNU General Public License         *
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.     *
 ******************************************************************************/

package com.chrulri.droidtv;

import static com.chrulri.droidtv.utils.StringUtils.NEWLINE;
import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.VideoView;

import com.chrulri.droidtv.utils.ErrorUtils;
import com.chrulri.droidtv.utils.ParallelTask;
import com.chrulri.droidtv.utils.ProcessUtils;
import com.chrulri.droidtv.utils.StringUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * DVBlast wrapper activity <br/>
 * <br/>
 * README: {@link http://git.videolan.org/?p=dvblast.git;a=blob;f=README;h=
 * cda01aa2e0cf0999478a7dcb1d60305e5c8a7a7f
 * ;hb=350557c669ce3670b7ea1e252b11f261c0610239}
 */

public class StreamActivity extends Activity {
    private static final String TAG = (StreamActivity.class.getSimpleName()+"caramba");

    static final int DVBLAST = R.raw.dvblast_2_1_0;
    static final int DVBLASTCTL = R.raw.dvblastctl_2_1_0;

    public static final String EXTRA_CHANNELCONFIG = "channelconfig";

    public enum DvbType {
        ATSC, DVBT, DVBC, DVBS
    }

    public class FrontendStatus {
        public static final int HAS_SIGNAL = 0x001;
        public static final int HAS_CARRIER = 0x02;
        public static final int HAS_VITERBI = 0x04;
        public static final int HAS_SYNC = 0x08;
        public static final int HAS_LOCK = 0x0F;
        public static final int REINIT = 0x10;

        public static final int SIGNAL_MAXVALUE = 0xFFFF;

        public int status;
        public long ber;
        public int signal;
        public int snr;

        @Override
        public String toString() {
            return String.format(
                    "FrontendStatus[status=%X, ber=%X, signal=%X, snr=%X]",
                    status, ber, signal, snr);
        }
    }

    static final String[] ENVP_TMPDIR = {
            "TMPDIR=."
    };

    static final String UDP_IP = "127.0.0.1";
    static final int UDP_PORT = 1555;

    // static final String HTTP_IP = "0.0.0.0"; // debug
    static final String HTTP_IP = "127.0.0.1";
    static final int HTTP_PORT = 1666;
    static final String HTTP_HEADER = "HTTP/1.1 200 OK" + NEWLINE +
            "Content-Type: video/mp2t" + NEWLINE +
            "Connection: keep-alive" + NEWLINE + NEWLINE;

    public static final String SERVICE_URL = String.format("http://127.0.0.1:%d/tv.ts",
            HTTP_PORT);

    /**
     * ip:port 1 serviceid
     */
    static final String DVBLAST_CONFIG_CONTENT = UDP_IP + ":" + UDP_PORT + " 1 %d";
    static final String DVBLAST_CONFIG_FILENAME = "dvblast.conf";
    static final String DVBLAST_SOCKET = "droidtv.socket";
    static final String DVBLAST_LOG = "dvblast.log";
    static final int DVBLAST_CHECKDELAY = 500;

    private FrontendStatus mFrontendStatus;
    private String mChannelName;
    private String mChannelConfig;
    private final Handler mHandler = new Handler();
    private AsyncDvblastCtlTask mDvblastCtlTask;
    private VideoView mVideoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.stream);
        mChannelConfig = getIntent().getStringExtra(EXTRA_CHANNELCONFIG);
        mVideoView = (VideoView) findViewById(R.id.stream_video);
        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                Log.d(TAG, "onPrepared(" + mp + ")");
            }
        });
        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.d(TAG, "onError(" + mp + "," + what + "," + extra + ")");
                finish(); // return to channel list
                return true;
            }
        });
        mVideoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.d(TAG, "onCompletion(" + mp + ")");
                finish(); // return to channel list
            }
        });
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        Intent intent = new Intent(this, StreamService.class);
        intent.putExtra(StreamService.EXTRA_CHANNELCONFIG, mChannelConfig);
        removeSocketFile();
        startService(intent);
        mDvblastCtlTask = new AsyncDvblastCtlTask();
        mDvblastCtlTask.execute();
        String[] params = mChannelConfig.split(":");
        mChannelName = params[0];
        updateTitle();
        Uri uriUrl = Uri.parse("rtp://127.0.0.1:1555");
        Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
        startActivity(launchBrowser);
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
    }
 
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        ProcessUtils.finishTask(mDvblastCtlTask, false);
        stopService(new Intent(this, StreamService.class));
    }

    /**
     * @param channelconfig
     * @return channel name
     */

    class AsyncDvblastCtlTask extends ParallelTask {

        final String TAG = StreamActivity.TAG + "." + AsyncDvblastCtlTask.class.getSimpleName();

        @Override
        protected void doInBackground() {
            Log.d(TAG, ">>>");
            try {
                Thread.sleep(DVBLAST_CHECKDELAY);
            } catch (InterruptedException e) {
                // nop
            }
            final FrontendStatus status = new FrontendStatus();
            mFrontendStatus = status;
            while (!isCancelled()) {
                try {
                    Process dvblastctl = ProcessUtils.runBinary(StreamActivity.this, DVBLASTCTL,
                            ENVP_TMPDIR,
                            "-r", DVBLAST_SOCKET, "-x", "xml", "fe_status");
                    int exitCode = dvblastctl.waitFor();
                    if (exitCode != 0) {
                        Log.w(TAG, "exited with " + exitCode);
                        continue;
                    }
                    Document dom = getDomElement(dvblastctl.getInputStream());
                    NodeList statusList = dom.getElementsByTagName("STATUS");
                    for (int i = 0; i < statusList.getLength(); i++) {
                        Node node = statusList.item(i);
                        String statusName = node.getAttributes().getNamedItem("status")
                                .getNodeValue();
                        if ("HAS_SIGNAL".equals(statusName)) {
                            status.status |= FrontendStatus.HAS_SIGNAL;
                        } else if ("HAS_CARRIER".equals(statusName)) {
                            status.status |= FrontendStatus.HAS_CARRIER;
                        } else if ("HAS_VITERBI".equals(statusName)) {
                            status.status |= FrontendStatus.HAS_VITERBI;
                        } else if ("HAS_SYNC".equals(statusName)) {
                            status.status |= FrontendStatus.HAS_SYNC;
                        } else if ("HAS_LOCK".equals(statusName)) {
                            status.status |= FrontendStatus.HAS_LOCK;
                        } else if ("REINIT".equals(statusName)) {
                            status.status |= FrontendStatus.REINIT;
                        }
                    }
                    NodeList valueList = dom.getElementsByTagName("VALUE");
                    for (int i = 0; i < valueList.getLength(); i++) {
                        Node node = valueList.item(i);
                        Node valueNode = node.getAttributes().item(0);
                        String valueName = valueNode.getNodeName();
                        String value = valueNode.getNodeValue();
                        if ("bit_error_rate".equalsIgnoreCase(valueName)) {
                            status.ber = Long.parseLong(value);
                        } else if ("signal_strength".equalsIgnoreCase(valueName)) {
                            status.signal = Integer.parseInt(value);
                        } else if ("snr".equalsIgnoreCase(valueName)) {
                            status.snr = Integer.parseInt(value);
                        }
                    }
                    publishProgress();
                } catch (Throwable t) {
                    Log.w(TAG, "dvblastctl", t);
                }
                // zZzZZZ..
                try {
                    Thread.sleep(DVBLAST_CHECKDELAY);
                } catch (InterruptedException e) {
                    continue;
                }
            }
            mFrontendStatus = null;
            Log.d(TAG, "<<<");
        }

        private void publishProgress() {
            Message msg = Message.obtain(mHandler, new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "fe_status: " + mFrontendStatus);
                    updateTitle();
                }
            });
            mHandler.sendMessage(msg);
        }

        private Document getDomElement(InputStream xmlStream) {
            Document doc = null;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder db = dbf.newDocumentBuilder();
                InputSource is = new InputSource();
                is.setByteStream(xmlStream);
                doc = db.parse(is);
            } catch (ParserConfigurationException e) {
                Log.e(TAG, e.getMessage());
                return null;
            } catch (SAXException e) {
                Log.e("Error: ", e.getMessage());
                return null;
            } catch (IOException e) {
                Log.e("Error: ", e.getMessage());
                return null;
            }
            return doc;
        }

    }

    private void updateTitle() {
        String str = mChannelName;
        if (mFrontendStatus != null) {
            str += String.format("  [Signal: %2d%%, Error: %d]",
                    (mFrontendStatus.signal * 100 / FrontendStatus.SIGNAL_MAXVALUE),
                    (mFrontendStatus.ber));
        }
        setTitle(str);
    }

    private void removeSocketFile() {
        File f = new File(getCacheDir(), DVBLAST_SOCKET);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "unable to delete " + DVBLAST_SOCKET);
        }
    }

}
