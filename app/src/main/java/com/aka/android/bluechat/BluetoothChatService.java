package com.aka.android.bluechat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.UUID;

import static com.aka.android.bluechat.MessageInstance.DATA_AUDIO;
import static com.aka.android.bluechat.MessageInstance.DATA_IMAGE;
import static com.aka.android.bluechat.MessageInstance.DATA_TEXT;

public class BluetoothChatService {

    private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;

    private static final String TAG = "BluetoothChatService";

    private static final String NAME = "BluetoothChat";
    private static final UUID MY_UUID = UUID.fromString("188c5bda-d1b6-464a-8074-c5deaad3fa36");

    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;


    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;


    public static final String DEVICE_NAME = "device_name";
    public static final String DEVICE_ADDRESS = "device_address";
    public static final String TOAST = "toast";

    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    public static final int MESSAGE_READ_IMAGE = 6;
    public static final int MESSAGE_READ_AUDIO = 7;
    public static final int MESSAGE_READ_TEXT = 8;

    public static final int MESSAGE_WRITE_IMAGE = 9;
    public static final int MESSAGE_WRITE_AUDIO = 10;
    public static final int MESSAGE_WRITE_TEXT = 11;

    private int mState;
    private int mNewState;


    public BluetoothChatService(Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mNewState = mState;
        mHandler = handler;
    }

    private class AcceptThread extends Thread {

        private final BluetoothServerSocket mServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            try {
                tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Dinleme başarısız.", e);
            }

            mServerSocket = tmp;
            mState = STATE_LISTEN;
        }

        @Override
        public void run() {

            BluetoothSocket socket = null;

            while (mState != STATE_CONNECTED) {
                try {
                    socket = mServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Bağlantı onayı başarısız.", e);
                    break;
                }

                // bağlandıktan sonraki işlemler
                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                connected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "İstenmeyen soket kapatılamıyor.", e);
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Server'i kapatma başarısız.", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mSocket;
        private final BluetoothDevice mDevice;

        public ConnectThread(BluetoothDevice device) {
            mDevice = device;
            BluetoothSocket tmp = null;

            // Verilen BluetoothDevice dan socket oluşturma
            try {
                tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Client Thread oluşturma başarısız.", e);
            }
            mSocket = tmp;
            mState = STATE_CONNECTING;
        }

        public void run() {
            setName("ConnectThread");

            // Herzaman cihaz taramayı kapatmalıyız çünkü
            // bu bağlantıyı yavaşlatır.
            mAdapter.cancelDiscovery();

            // BluetoothSocket ile bağlantı kurma
            try {
                mSocket.connect();
            } catch (IOException e) {
                try {
                    mSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "Bağlantı hatası sırasında soket kapatılamıyor.", e2);
                }
                connectionFailed();
                return;
            }

            // ConnectThread resetleniyor çünkü işimiz bitti
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            connected(mSocket, mDevice);
        }

        public void cancel() {
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Client Thread kapatma başarısız.", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mSocket;
        private final InputStream mInStream;
        private final OutputStream mOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Geçici soketler oluşturma işlemi başarısız.", e);
            }

            mInStream = tmpIn;
            mOutStream = tmpOut;
            mState = STATE_CONNECTED;
        }


        // Gelen veriyi okuma kısmı

        public void run() {
            final int BUFFER_SIZE = 16384;
            byte[] bufferData = new byte[BUFFER_SIZE];
            int numOfPackets = 0;
            int datatype = 0;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            while (mState == STATE_CONNECTED) {
                try {
                    int numOfBytes = mInStream.read(bufferData);
                    byte[] trimmedBufferData = Arrays.copyOf(bufferData, numOfBytes);
                    bufferData = new byte[BUFFER_SIZE];
                    ByteBuffer tempBuffer = ByteBuffer.wrap(trimmedBufferData);

                    String macAddress = mSocket.getRemoteDevice().getAddress();
                    String userName = mSocket.getRemoteDevice().getName();

                    MessageInstance dataSent = new MessageInstance();
                    dataSent.setMacAddress(macAddress);
                    dataSent.setUserName(userName);


                    if (datatype == 0) {
                        datatype = tempBuffer.getInt();
                        Log.d(TAG, "Veri tipi: " + datatype);
                    }
                    if (numOfPackets == 0) {
                        numOfPackets = tempBuffer.getInt();
                        Log.d(TAG, "Paket boyutu: " + numOfPackets);
                    }
                    byte[] dst = new byte[tempBuffer.remaining()];
                    tempBuffer.get(dst);
                    bos.write(dst);
                    // Aşağıdaki koşul, bir mesaj oluşturmak için gerekli tüm baytları alıp almadığımızı kontrol eder.
                    if (bos.size() == numOfPackets) {
                        switch(datatype) {
                            case DATA_AUDIO:
                                Log.d(TAG, "Soketten audio verisi okunuyor.");
                                dataSent.setData(bos.toByteArray());
                                dataSent.setDataType(DATA_AUDIO);
                                Message audioMsg = mHandler.obtainMessage(MESSAGE_READ_AUDIO, -1,
                                        datatype, dataSent);
                                audioMsg.sendToTarget();
                                break;
                            case DATA_TEXT:
                                Log.d(TAG, "Soketten text verisi okunuyor.");
                                dataSent.setData(bos.toByteArray());
                                dataSent.setDataType(DATA_TEXT);
                                Message textMsg = mHandler.obtainMessage(MESSAGE_READ_TEXT, -1,
                                        datatype, dataSent);
                                textMsg.sendToTarget();
                                break;
                            case DATA_IMAGE:
                                Log.d(TAG, "Soketten görüntü verisi okunuyor.");
                                String decodedString = new String(bos.toByteArray(),
                                        Charset.defaultCharset());
                                byte[] decodedStringArray = Base64.decode(decodedString, Base64.DEFAULT);
                                Bitmap bp = BitmapFactory.decodeByteArray(decodedStringArray,
                                        0, decodedStringArray.length);

                                dataSent.setDataType(DATA_IMAGE);
                                dataSent.setData(bp);

                                Message imgMsg = mHandler.obtainMessage(MESSAGE_READ_IMAGE,
                                        -1, datatype, dataSent);
                                imgMsg.sendToTarget();
                                break;
                        }
                        // yeni mesaj için hazır hale getirme
                        datatype = 0;
                        numOfPackets = 0;
                        bos = new ByteArrayOutputStream();
                    }
                } catch (IOException e) {
                    Log.d(TAG, "Input stream bağlantısı kesildi.", e);
                    connectionLost();
                    break;
                }
            }
        }

        public void write(byte[] bytes, int datatype, String timeSent) {
            try {
                Message writtenMsg = null;
                ByteArrayOutputStream tempOutputStream = new ByteArrayOutputStream();
                ByteBuffer tempBuffer = ByteBuffer.allocate(bytes.length + 8);
                MessageInstance dataSent = new MessageInstance();
                String macAddress = mSocket.getRemoteDevice().getAddress();
                String userName = mSocket.getRemoteDevice().getName();
                dataSent.setMacAddress(macAddress);
                dataSent.setUserName(userName);
                dataSent.setTime(timeSent);
                if (datatype == DATA_IMAGE) {

                    tempBuffer.putInt(DATA_IMAGE);
                    ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
                    imageStream.write(bytes);
                    String decodedString = new String(imageStream.toByteArray(),
                            Charset.defaultCharset());
                    byte[] decodedStringArray = Base64.decode(decodedString, Base64.DEFAULT);
                    Bitmap bp = BitmapFactory.decodeByteArray(decodedStringArray,
                            0, decodedStringArray.length);

                    dataSent.setData(bp);
                    dataSent.setDataType(DATA_IMAGE);

                    writtenMsg = mHandler.obtainMessage(MESSAGE_WRITE_IMAGE, -1, DATA_IMAGE,
                            dataSent);
                    imageStream.close();

                } else if (datatype == DATA_TEXT) {

                    tempBuffer.putInt(DATA_TEXT);
                    dataSent.setData(bytes);
                    dataSent.setDataType(DATA_TEXT);

                    writtenMsg = mHandler.obtainMessage(MESSAGE_WRITE_TEXT, -1, DATA_TEXT,
                            dataSent);

                } else if (datatype == DATA_AUDIO) {
                    tempBuffer.putInt(DATA_AUDIO);
                    dataSent.setData(bytes);
                    dataSent.setDataType(DATA_AUDIO);

                    writtenMsg = mHandler.obtainMessage(MESSAGE_WRITE_AUDIO, -1, DATA_AUDIO,
                            dataSent);
                }
                tempBuffer.putInt(bytes.length);
                tempBuffer.put(bytes);
                tempOutputStream.write(tempBuffer.array());
                mOutStream.write(tempOutputStream.toByteArray());
                tempOutputStream.close();
                if (writtenMsg != null) {
                    writtenMsg.sendToTarget();
                }
            } catch (IOException e) {
                Log.e(TAG, "Veri gönderilirken hata oluştu.", e);

                Message writeErrorMsg = mHandler.obtainMessage(MESSAGE_TOAST);
                Bundle bundle = new Bundle();
                bundle.putString("toast", "Cihaz bağlantısı koptu. Veri gönderilemiyor.");
                writeErrorMsg.setData(bundle);
                mHandler.sendMessage(writeErrorMsg);
            }
        }

        public void cancel() {
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Bağlantı soketinin close() metodu hata verdi.", e);
            }
        }
    }

    // Activity e bir hata mesajı yolla
    private void connectionFailed() {
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Cihaza bağlanılamıyor.");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        mState = STATE_NONE;
        // UI başlığını güncelle
        updateUserInterfaceTitle();

        BluetoothChatService.this.start();
    }

    // Activity e bir hata mesajı yolla
    private void connectionLost() {
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Cihaz bağlantısı koptu.");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        mState = STATE_NONE;

        updateUserInterfaceTitle();

        BluetoothChatService.this.start();
    }

    /**
     * Uzak bir cihazla bağlantı başlatmak için ConnectThread'i başlatın.
     *
     * @param device Bağlanılacak bluetooth cihazı
     */

    public synchronized void connect(BluetoothDevice device) {
        Log.d(TAG, "Şu cihaza bağlanılıyor: " + device);

        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        updateUserInterfaceTitle();
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();

        Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(DEVICE_NAME, device.getName());
        bundle.putString(DEVICE_ADDRESS, device.getAddress());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        updateUserInterfaceTitle();
    }

    public synchronized void start() {
        Log.d(TAG, "start");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }

        updateUserInterfaceTitle();
    }

    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        mState = STATE_NONE;
        updateUserInterfaceTitle();
    }


    public void write(byte[] out, int datatype, String timeSent) {
        // Geçici bir obje oluştur
        ConnectedThread r;
        // ConnectedThread in bir kopyasını senkronize et
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }

        r.write(out, datatype, timeSent);
    }

    private synchronized void updateUserInterfaceTitle() {
        mState = getState();
        Log.d(TAG, "updateUserInterfaceTitle() " + mNewState + " -> " + mState);
        mNewState = mState;

        // Handler a güncel bağlantı durumu verilir
        // UI activity durumu güncellenir.
        mHandler.obtainMessage(MESSAGE_STATE_CHANGE, mNewState, -1).sendToTarget();
    }

    public synchronized int getState() {
        return mState;
    }

}
