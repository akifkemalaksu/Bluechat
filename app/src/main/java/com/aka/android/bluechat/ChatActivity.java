package com.aka.android.bluechat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static android.os.Environment.getExternalStorageDirectory;
import static com.aka.android.bluechat.BluetoothChatService.DEVICE_ADDRESS;
import static com.aka.android.bluechat.BluetoothChatService.MESSAGE_READ_AUDIO;
import static com.aka.android.bluechat.BluetoothChatService.MESSAGE_READ_IMAGE;
import static com.aka.android.bluechat.BluetoothChatService.MESSAGE_READ_TEXT;
import static com.aka.android.bluechat.BluetoothChatService.MESSAGE_WRITE_AUDIO;
import static com.aka.android.bluechat.BluetoothChatService.MESSAGE_WRITE_IMAGE;
import static com.aka.android.bluechat.BluetoothChatService.MESSAGE_WRITE_TEXT;
import static com.aka.android.bluechat.ChatMessages.compressBitmap;
import static com.aka.android.bluechat.MainActivity.mBluetoothAdapter;
import static com.aka.android.bluechat.MessageInstance.DATA_AUDIO;
import static com.aka.android.bluechat.MessageInstance.DATA_IMAGE;
import static com.aka.android.bluechat.MessageInstance.DATA_TEXT;

public class ChatActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_CONNECT_DEVICE = 3;
    private BluetoothChatService mChatService = null;

    private static final int SELECT_IMAGE = 11;
    private static final int MY_PERMISSION_REQUEST_READ_EXTERNAL_STORAGE = 2;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private MediaRecorder mRecorder = null;
    private MediaPlayer mPlayer = null;

    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    private String mConnectedDeviceName = null;
    private String mConnectedDeviceAddress = null;

    private StringBuffer mOutStringBuffer;
    private ListView mConversationView;
    private EditText mEditText;
    private ImageButton mButtonSend;
    private TextView connectionStatus;
    ChatMessageAdapter chatMessageAdapter;
    String fileName = null;
    Bitmap imageBitmap;

    private ChatMessages db;

    private UserInfo user;

    private final static String TAG = "ChatActivity";
    private final static int MAX_IMAGE_SIZE = 200000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat_activity_main);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        db = new ChatMessages(getApplicationContext());

        // main activity den seçilen konuşmadaki cihaz bilgileri alınıyor.
        UserInfo usersInfo = (UserInfo) getIntent()
                .getSerializableExtra("USERS-INFO");

        user = usersInfo;

        init();


        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else if (mChatService == null) {
            setupChat();
        }

        mConversationView = findViewById(R.id.message_history);
        chatMessageAdapter = new ChatMessageAdapter(ChatActivity.this, R.layout.chat_message);
        mConversationView.setAdapter(chatMessageAdapter);

        mConversationView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                MessageInstance msg = (MessageInstance) parent.getItemAtPosition(position);
                if (msg.audioFile != null) {
                    mPlayer = MediaPlayer.create(ChatActivity.this, Uri.fromFile(msg.audioFile));
                    mPlayer.start();
                }
            }
        });

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);


        final ImageButton btnRecord = findViewById(R.id.btn_record);
        btnRecord.setOnClickListener(new View.OnClickListener() {
            boolean mStartRecording = true;

            @Override
            public void onClick(View view) {
                onRecord(mStartRecording);

                if (mStartRecording) {
                    btnRecord.setImageResource(R.drawable.ic_stop_black_24dp);
                } else {
                    btnRecord.setImageResource(R.drawable.ic_mic_black_24dp);
                }
                mStartRecording = !mStartRecording;
            }
        });
    }

    public void init() {
        connectionStatus = findViewById(R.id.connection_status);
        mEditText = findViewById(R.id.edit_text_text_message);
        mButtonSend = findViewById(R.id.btn_send);

        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Cihaz Bluetooth'u desteklemiyor.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadChatHistory(getIntent());
        /*
         onResume() öğesinde bu kontrolün yapılması, onStart() sırasında BT'nin etkinleştirilmediği durumu kapsar,
         bu nedenle etkinleştirmek için duraklatıldı.
         onResume(), ACTION_REQUEST_ENABLE etkinliği döndüğünde çağrılır.
         */
        if (mChatService != null) {
            // Sadece durum STATE_NONE olursa, Henüz bir bağlantıya sahip olmadığımızı biliyoruz.
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // BluetoothChatService başlayılıyor.
                mChatService.start();
            }
        }
    }

    private void startPreviousChat() {
        if (user == null) {
            return;
        }

        Log.d(TAG, "Şu cihaza bağlanılıyor, " + user.macAddress);
        connectDevice(user.macAddress);
    }

    private void loadChatHistory(Intent intent) {
        Log.d(TAG, "Geçmiş konuşmalar yükleniyor.");

        user = (UserInfo) intent.getSerializableExtra("USERS-INFO");

        if (user == null) {
            Log.d(TAG, "User boş.");
            return;
        }

        chatMessageAdapter.clear();

        List<ChatMessage> readMessages = getAllMessages(user, "Received");
        List<ChatMessage> sentMessages = getAllMessages(user, "Sent");
        List<ChatMessage> combinedMessages = ChatMessages.combineMessages(readMessages, sentMessages);

        showChatHistory(combinedMessages);
    }

    void showChatHistory(List<ChatMessage> messages) {
        String receivedFrom = null;
        for (ChatMessage message : messages) {
            Log.d(TAG, message.user);
            Log.d(TAG, Integer.toString(message.dataType));
            if (message.user.equals("Me")) {

                if (message.dataType == DATA_IMAGE) {
                    chatMessageAdapter.add(new MessageInstance(true, message.image));
                } else if (message.dataType == DATA_TEXT) {
                    chatMessageAdapter.add(new MessageInstance(true,
                            message.user + ": " + message.message + "\n ("
                                    + message.timeStamp + ")"));
                } else {
                    chatMessageAdapter.add(new MessageInstance(true, message.audioFile));
                }
            } else {
                if (message.dataType == DATA_IMAGE) {
                    chatMessageAdapter.add(new MessageInstance(false, message.image));
                } else if (message.dataType == DATA_TEXT){
                    chatMessageAdapter.add(new MessageInstance(false,
                            message.user + ": " + message.message + "\n ("
                                    + message.timeStamp + ")"));
                } else {
                    chatMessageAdapter.add(new MessageInstance(false, message.audioFile));
                }
            }
            if (receivedFrom == null) {
                receivedFrom = message.user;
            }
            chatMessageAdapter.notifyDataSetChanged();
        }

        setChatTitle(user.getName());
    }

    public void setChatTitle(String deviceName){
        // Konuşmadaki cihazın adını başlığa girme
        setTitle(deviceName);
    }

    List<ChatMessage> getAllMessages(UserInfo usersInfo, String messageType) {
        List<ChatMessage> messages = new ArrayList<>();

        String macAddress = usersInfo.macAddress;
        String userName = usersInfo.name;

        List<ChatMessage> readMessages;

        if (messageType.equals("Sent")) {
            readMessages = db.retrieveSentMessages(macAddress);
        } else {
            readMessages = db.retrieveReceivedMessages(macAddress);
        }

        // mesajları gelen giden olarak ayırma
        for (ChatMessage message : readMessages) {
            if (messageType.equals("Sent")) {
                message.user = "Me";
            } else {
                message.user = userName;
            }
            messages.add(message);
        }

        return messages;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mRecorder != null) {
            mRecorder.release();
            mRecorder = null;
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "destroy çağrıldı.");
        super.onDestroy();

        if (mChatService != null) {
            mChatService.stop();
        }
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "Geri tuşuna basıldı.");

        if (mChatService != null) {
            mChatService.stop();
        }
        user = null;
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.menu_make_discoverable:
                ensureDiscoverable();
                return true;

            case R.id.menu_search_devices:
                Intent bluetoothIntent = new Intent(getApplicationContext(),
                        DeviceListActivity.class);
                startActivityForResult(bluetoothIntent, REQUEST_CONNECT_DEVICE);
                break;

            case R.id.device_connect_disconnect:
                Log.d(TAG, "Direkt bağlantı kurmaya çalışılıyor.");
                if (user == null) {
                    Toast.makeText(this, "Direkt bağlantı mümkün değil.",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                startPreviousChat();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void PhotoMessage(View view) {
        permissionCheck();
    }


    public void permissionCheck() {


        if (ContextCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)) {

                Toast.makeText(this, "İzinleri reddettiniz. " +
                        "Lütfen izin isteklerini kabul edin.", Toast.LENGTH_SHORT).show();
            } else {
                ActivityCompat.requestPermissions(this, new String[]
                                {Manifest.permission.READ_EXTERNAL_STORAGE},
                        MY_PERMISSION_REQUEST_READ_EXTERNAL_STORAGE);
            }
        } else {
            requestImageFromGallery();
        }
    }

    public void requestImageFromGallery() {
        Intent attachImageIntent = new Intent();
        attachImageIntent.setType("image/*");
        attachImageIntent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(attachImageIntent, "Fotoğraf Seç"),
                SELECT_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_ENABLE_BT:
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Bluetooth Açıldı.", Toast.LENGTH_SHORT).show();

                    // Bluetooth bağlantısını gerçekleştirmek için BluetoothChatService uygulamasını başlatın.
                    mChatService = new BluetoothChatService(mHandler);
                } else {
                    Toast.makeText(this, "Bluetooth açık değil.", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;

            case SELECT_IMAGE:
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null) {
                        try {
                            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(),
                                    data.getData());

                            byte[] imageSend;
                            try {
                                imageSend = compressBitmap(bitmap, true);
                            } catch (NullPointerException e) {
                                Log.d(TAG, "Görüntü sıkıştırılamaz.");
                                Toast.makeText(getApplicationContext(), "Görüntü bulunamadı " +
                                                "veya göndermek için çok büyük.",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }

                            Calendar calendar = Calendar.getInstance();
                            String timeSent = sdf.format(calendar.getTime());

                            if (imageSend.length > MAX_IMAGE_SIZE) {
                                Toast.makeText(getApplicationContext(), "Görüntü çok büyük.",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }

                            mChatService.write(imageSend, DATA_IMAGE, timeSent);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                break;


            case REQUEST_CONNECT_DEVICE:
                // DeviceListActivity bağlantı kurulacak bir cihaz döndürdüğünde
                if (resultCode == Activity.RESULT_OK) {
                    String macAddress = data.getExtras()
                            .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    connectDevice(macAddress);
                }
                break;

        }
    }

    private void connectDevice(String macAddress) {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(macAddress);
        mConnectedDeviceAddress = macAddress;
        mChatService.connect(device);
    }

    static final SimpleDateFormat sdf = new SimpleDateFormat("YY:MM-dd HH:mm:ss");

    String prevSendTime = null;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            connectionStatus.setText(getResources().getString(R.string.connected));
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            connectionStatus.setText(getResources().getString(R.string.disconnected));
                            break;
                    }
                    break;


                case MESSAGE_WRITE_TEXT:
                    MessageInstance textWriteInstance = (MessageInstance) msg.obj;
                    byte[] writeBuf = (byte[]) textWriteInstance.getData();

                    String writeMessage = new String(writeBuf);
                    Calendar calendar = Calendar.getInstance();
                    String txtWriteTime = sdf.format(calendar.getTime());

                    String time = textWriteInstance.getTime();

                    if (prevSendTime == null) {
                        prevSendTime = time;
                    } else if (prevSendTime.equals(time)) {
                        Log.d(TAG, "Zaman eşit, mesaj tekrarlanmadı.");
                        break;
                    }
                    prevSendTime = time;

                    db.insertSentMessage(txtWriteTime, mConnectedDeviceAddress, writeMessage,
                            DATA_TEXT);

                    String writeDisplayMessage = "Me: " + writeMessage + "\n"
                            + "(" + txtWriteTime + ")";

                    chatMessageAdapter.add(new MessageInstance(true, writeDisplayMessage));
                    chatMessageAdapter.notifyDataSetChanged();
                    break;

                case MESSAGE_WRITE_AUDIO:
                    MessageInstance audioWriteInstance = (MessageInstance) msg.obj;
                    String connectedMacAddress = audioWriteInstance.getMacAddress();
                    Calendar AudioCalendar = Calendar.getInstance();
                    String AudioWriteTime = sdf.format(AudioCalendar.getTime());

                    time = audioWriteInstance.getTime();

                    if (prevSendTime == null) {
                        prevSendTime = time;
                    } else if (prevSendTime.equals(time)) {
                        Log.d(TAG, "Zaman eşit, mesaj tekrarlanmadı.");
                        break;
                    }
                    prevSendTime = time;

                    Log.d(TAG, "Audio şurada saklanmalıdır: " + fileName);
                    File f = new File(fileName);
                    chatMessageAdapter.add(new MessageInstance(true, f));
                    chatMessageAdapter.notifyDataSetChanged();

                    db.insertSentMessage(AudioWriteTime, connectedMacAddress, f.toString(), DATA_AUDIO);

                    break;

                case MESSAGE_WRITE_IMAGE:
                    Log.d(TAG, "Görüntü yazılıyor.");
                    MessageInstance imageWriteInstance = (MessageInstance) msg.obj;
                    String userMacAddress = imageWriteInstance.getMacAddress();

                    Calendar ImageCalendar = Calendar.getInstance();
                    String imageWriteTime = sdf.format(ImageCalendar.getTime());

                    time = imageWriteInstance.getTime();

                    if (prevSendTime == null) {
                        prevSendTime = time;
                    } else if (prevSendTime.equals(time)) {
                        Log.d(TAG, "Zaman eşit, mesaj tekrarlanmadı.");
                        break;
                    }
                    prevSendTime = time;

                    imageBitmap = (Bitmap) imageWriteInstance.getData();
                    byte[] writeDecodedStringArray = compressBitmap(imageBitmap, false);

                    db.insertSentMessage(imageWriteTime, userMacAddress,
                            writeDecodedStringArray, DATA_IMAGE);
                    Log.d(TAG, "DB içine eklenen yazma görüntüsü");

                    if (imageBitmap != null) {
                        chatMessageAdapter.add(new MessageInstance(true, imageBitmap));
                        chatMessageAdapter.notifyDataSetChanged();
                    } else {
                        Log.e(TAG, "Görüntü bitmap'i boş");
                    }
                    break;

                case MESSAGE_READ_IMAGE:
                    MessageInstance msgImgData = (MessageInstance) msg.obj;
                    userMacAddress = msgImgData.getMacAddress();
                    Calendar calTest = Calendar.getInstance();
                    String readImageTime = sdf.format(calTest.getTime());

                    if (msgImgData.getDataType() == DATA_IMAGE) {
                        imageBitmap = (Bitmap) msgImgData.getData();

                        // Sıkıştır ve veritabanında sakla
                        byte[] decodedStringArray = compressBitmap(imageBitmap, false);

                        db.insertReceivedMessage(readImageTime, userMacAddress, decodedStringArray,
                                DATA_IMAGE);
                        Log.d(TAG, "Görüntü veritabanına depolandı.");

                        if (imageBitmap != null) {
                            chatMessageAdapter.add(new MessageInstance(false, imageBitmap));
                            chatMessageAdapter.notifyDataSetChanged();
                        } else {
                            Log.e(TAG, "Görüntü bitmap'i boş");
                        }
                    }

                    Log.d(TAG, "Görüntü şuradan okundu, " + msgImgData.getUserName() + ": "
                        + msgImgData.getMacAddress());

                    break;

                case MESSAGE_READ_TEXT:
                    MessageInstance msgTextData = (MessageInstance) msg.obj;
                    byte[] readBuf = (byte[]) msgTextData.getData();

                    Calendar cal = Calendar.getInstance();
                    String readTime = sdf.format(cal.getTime());

                    String message = new String(readBuf);

                    db.insertReceivedMessage(readTime, mConnectedDeviceAddress,
                            message, DATA_TEXT);

                    String displayMessage = msgTextData.getUserName() + ": " + message + "\n"
                            + "(" + readTime + ")";

                    chatMessageAdapter.add(new MessageInstance(false, displayMessage));
                    chatMessageAdapter.notifyDataSetChanged();

                    Log.d(TAG, "Metin şuradan okundu, " + msgTextData.getUserName() + ": "
                            + msgTextData.getMacAddress());
                    break;

                case MESSAGE_READ_AUDIO:
                    MessageInstance msgAudioData = (MessageInstance) msg.obj;
                    connectedMacAddress = msgAudioData.getMacAddress();
                    Calendar readAudioCal = Calendar.getInstance();
                    readTime = sdf.format(readAudioCal.getTime());
                    String filename = getFilename();
                    FileOutputStream fos;

                    try {
                        if (filename != null) {
                            byte[] buff = (byte[]) msgAudioData.getData();
                            Log.d(TAG, "ZAMAN: " + readTime);
                            fos = new FileOutputStream(filename);
                            fos.write(buff);
                            fos.flush();
                            fos.close();
                            chatMessageAdapter.add(new MessageInstance(false, new File(filename)));
                            chatMessageAdapter.notifyDataSetChanged();

                            db.insertReceivedMessage(readTime, connectedMacAddress, filename,
                                    DATA_AUDIO);
                        }
                    } catch (Exception e) {
                        Toast.makeText(ChatActivity.this, "Dosya kaydedilemiyor.",
                                Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Dosya kaydedilemiyor.", e);
                    }

                    Log.d(TAG, "Ses şuradan kaydedildi, " + msgAudioData.getUserName() + ": "
                        + msgAudioData.getMacAddress());
                    break;

                case MESSAGE_DEVICE_NAME:
                    // bağlanılan cihazın adını kaydetme
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    mConnectedDeviceAddress = msg.getData().getString(DEVICE_ADDRESS);
                    if (null != getApplicationContext()) {
                        Toast.makeText(getApplicationContext(), "Şuna bağlanıldı, "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }

                    setChatTitle(mConnectedDeviceName);
                    // user i veritabanında kontrol et, eğer varsa mesajları yükle
                    // yoksa ekranı temizle

                    Log.d(TAG, "Veritabanı kontrolünden önce");

                    if (db.isUserInDb(mConnectedDeviceAddress)) {
                        Log.d(TAG, "User veritabanında kayıtlı.");
                        Intent intent = new Intent();
                        UserInfo user = new UserInfo(mConnectedDeviceName, mConnectedDeviceAddress);
                        intent.putExtra("USERS-INFO", user);
                        loadChatHistory(intent);
                    } else {
                        Log.d(TAG, "User veritabanında kayıtlı değil.");
                        try {
                            chatMessageAdapter.clear();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    // user name ve user mac adresini veritabanına ekle
                    db.insertUserName(mConnectedDeviceAddress, mConnectedDeviceName);

                    break;
                case MESSAGE_TOAST:
                    if (null != getApplicationContext()) {
                        Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                                Toast.LENGTH_SHORT).show();

                        String toastMsg = msg.getData().getString(TOAST);

                        // Bağlantıyı her zaman sıfırlandığından emin ol.
                        if (toastMsg.equals("Cihaza bağlanılamıyor.") ||
                                toastMsg.equals("Cihaz bağlantısı koptu.")) {
                            Log.d(TAG, "Bağlantı kaybedildi: " + toastMsg);
                            onBackPressed();
                        }
                    }
                    break;
            }
        }
    };

    private void setupChat() {
        mEditText.setOnEditorActionListener(mWriteListener);

        mButtonSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String message = mEditText.getText().toString();
                sendMessage(message);
            }
        });
        mChatService = new BluetoothChatService(mHandler);
        mOutStringBuffer = new StringBuffer("");
    }

    private void sendMessage(String message) {
        if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(getApplicationContext(), R.string.not_connected,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (message.length() > 0) {
            System.out.println("Message Length = " + message.length());
            Calendar calendar = Calendar.getInstance();
            String timeSent = sdf.format(calendar.getTime());
            mChatService.write(message.getBytes(), DATA_TEXT, timeSent);

            mOutStringBuffer.setLength(0);
            mEditText.setText(mOutStringBuffer);
        }
    }

    private TextView.OnEditorActionListener mWriteListener
            = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                String message = view.getText().toString();
                sendMessage(message);
            }
            return true;
        }
    };

    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    private String getFilename() {
        String filepath = getExternalStorageDirectory().getPath();
        File appFolder = new File(filepath, "ChatApp");
        if (!appFolder.exists()) {
            if (!appFolder.mkdirs()) {
                Toast.makeText(this, "Uygulama klasörü oluşturulamadı. Depolama gerektiren herhangi bir aktivite askıya alınır.",
                        Toast.LENGTH_LONG).show();
                return null;
            }
        }
        return appFolder.getAbsolutePath() + File.separator + System.currentTimeMillis() + ".mp3";
    }

    private void onRecord(boolean start) {
        if (start) {
            startRecording();
        } else {
            stopRecording();
        }
    }


    private void startRecording() {
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        fileName = getFilename();
        Log.d("Kaydı Başlat :", fileName);
        mRecorder.setOutputFile(fileName);
        try {
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            Log.e(TAG, "Kayıt başarısız.", e);
        }
    }


    private void stopRecording() {
        Toast.makeText(this, "Kayıt durdu.", Toast.LENGTH_SHORT).show();
        if (null != mRecorder) {
            mRecorder.stop();
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
            if (mChatService != null) {
                try {
                    File f = new File(fileName);
                    FileInputStream fis = new FileInputStream(fileName);
                    byte[] buff = new byte[(int) f.length()];
                    fis.read(buff);
                    Calendar calendar = Calendar.getInstance();
                    String timeSent = sdf.format(calendar.getTime());
                    mChatService.write(buff, DATA_AUDIO, timeSent);
                    fis.close();
                } catch (Exception e) {
                    Log.e(TAG, "Veri kaydetmek için stream açılamadı", e);
                }
            }
        }
    }

    public void OpenMap(View view) {
        Intent mapIntent = new Intent(getApplicationContext(),MapsActivity.class);
        startActivity(mapIntent);
    }
}
