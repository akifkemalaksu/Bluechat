package com.aka.android.bluechat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.aka.android.bluechat.MessageInstance.DATA_AUDIO;
import static com.aka.android.bluechat.MessageInstance.DATA_IMAGE;
import static com.aka.android.bluechat.MessageInstance.DATA_TEXT;

/*
Her cihaz 2 veritabanına ait. Biri senin yolladığın mesajları kaydediyor.
Diğeri ise sana gönderilen mesajları kaydediyor.
Mesajın gönderilme zamanı ve user id (mac adres) si unique oluyor.
 */

class ChatMessage {
    String timeStamp;
    String message;
    String user;
    Bitmap image;
    File audioFile;
    int dataType;

    ChatMessage(String time, String message) {
        timeStamp = time;
        this.message = message;
        dataType = DATA_TEXT;
    }

    ChatMessage(String time, Bitmap image) {
        timeStamp = time;
        this.image = image;
        dataType = DATA_IMAGE;
    }

    ChatMessage(String time, File audioFile) {
        timeStamp = time;
        this.audioFile = audioFile;
        dataType = DATA_AUDIO;
    }
}

class ChatMessages extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "BlueToothMessenger";
    private static final int DATABASE_VERSION = 1;
    private static final String RECEIVED_MESSAGES_TABLE = "ReceivedMessages";
    private static final String SENT_MESSAGES_TABLE = "SentMessages";
    static final String USER_NAMES_TABLE = "UserNames";


    private static final String TIME_STAMP = "Time";
    private static final String USER_ID = "User";
    private static final String MESSAGE = "Message";
    private static final String USER_NAME = "UserName";
    private static final String IMAGE = "Image";
    private static final String AUDIO = "Audio";

    private static final String TAG = "Messages";

    ChatMessages(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        final String MESSAGES_COLUMNS = " ("
                + TIME_STAMP + " TEXT,"
                + USER_ID + " TEXT,"
                + MESSAGE + " TEXT,"
                + IMAGE + " BLOB,"
                + AUDIO + " TEXT,"
                + "PRIMARY KEY ("
                + TIME_STAMP + ", "
                + USER_ID + ") )";

        final String CREATE_RECEIVED_MESSAGES_TABLE = "CREATE TABLE "
                + RECEIVED_MESSAGES_TABLE + MESSAGES_COLUMNS;

        final String CREATE_SENT_MESSAGES_TABLE = "CREATE TABLE "
                + SENT_MESSAGES_TABLE + MESSAGES_COLUMNS;

        final String USERS_COLUMNS = " (" + USER_NAME + " TEXT,"
                + USER_ID + " TEXT, PRIMARY KEY (" + USER_ID + ") )";

        final String CREATE_USER_NAMES_TABLE = "CREATE TABLE " +
                USER_NAMES_TABLE + USERS_COLUMNS;

        Log.d(TAG, CREATE_RECEIVED_MESSAGES_TABLE);
        Log.d(TAG, CREATE_SENT_MESSAGES_TABLE);
        Log.d(TAG, CREATE_USER_NAMES_TABLE);

        db.execSQL(CREATE_RECEIVED_MESSAGES_TABLE);
        db.execSQL(CREATE_SENT_MESSAGES_TABLE);
        db.execSQL(CREATE_USER_NAMES_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + RECEIVED_MESSAGES_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + SENT_MESSAGES_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + USER_NAMES_TABLE);
        onCreate(db);
    }

    private void insertMessage(String timeStamp, String userId, Object message, String tableType,
                               int dataType) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues insertValues = new ContentValues();
        insertValues.put(TIME_STAMP, timeStamp);
        insertValues.put(USER_ID, userId);

        if (dataType == DATA_TEXT) {
            insertValues.put(MESSAGE, (String) message);
        }
        else if (dataType == DATA_IMAGE) {
            insertValues.put(IMAGE, (byte []) message);
        }
        else if (dataType == DATA_AUDIO) {
            insertValues.put(AUDIO, message.toString());
        }

        try {
            db.insert(tableType, null, insertValues);
        } catch (SQLiteConstraintException e) {
            Log.e(TAG, "Mesaj ekleme hatası");
        }
        db.close();
    }

    void insertUserName(String macAddress, String userName) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues insertValues = new ContentValues();
        insertValues.put(USER_NAME, userName);
        insertValues.put(USER_ID, macAddress);
        try {
            db.insert(USER_NAMES_TABLE, null, insertValues);
        } catch (SQLiteConstraintException e) {
            Log.e(TAG, "Veri ekleme hatası");
        }
        db.close();
    }

    void insertSentMessage(String timeStamp, String userId, Object message, int dataType) {
        insertMessage(timeStamp, userId, message, SENT_MESSAGES_TABLE, dataType);
    }

    void insertReceivedMessage(String timeStamp, String userId, Object message, int dataType) {
        insertMessage(timeStamp, userId, message, RECEIVED_MESSAGES_TABLE, dataType);
    }

    boolean isUserInDb(String macAddress) {
        String query = "SELECT " + USER_ID + " FROM " + USER_NAMES_TABLE
                + " WHERE " + USER_ID + " = '" + macAddress + "'";

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(query, null);

        if(cursor.moveToFirst()) {
            if (cursor.getString(0).equals(macAddress)) {
                return true;
            }
        }

        cursor.close();
        db.close();
        return false;
    }

    private ArrayList<ChatMessage> retrieveMessages(String userId, String tableType) {
        ArrayList<ChatMessage> userMessages = new ArrayList<>();
        String select = "SELECT " + TIME_STAMP + ", " + MESSAGE + ", " + IMAGE + ", " + AUDIO + " FROM " + tableType +
                " WHERE " + USER_ID + " = '" + userId + "'";

        Log.d(TAG, select);

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(select, null);
        Log.d(TAG, "mesajları alma");
        if(cursor.moveToFirst()) {
            do {
                String date = cursor.getString(cursor.getColumnIndex(TIME_STAMP));
                String message = cursor.getString(cursor.getColumnIndex(MESSAGE));
                byte[] image = cursor.getBlob(cursor.getColumnIndex(IMAGE));
                String audio = cursor.getString(cursor.getColumnIndex(AUDIO));

                ChatMessage cm = null;
                if (image != null) {
                    Log.d(TAG, "Mesaj boş, resim ekleniyor");
                    Bitmap bpImage = BitmapFactory.decodeByteArray(image, 0, image.length);
                    cm = new ChatMessage(date, bpImage);
                } else if (message != null){
                    Log.d(TAG, "Mesaj Ekleme");
                    cm = new ChatMessage(date, message);
                } else {
                    Log.d(TAG, "Ses dosyası ekleme");
                    cm = new ChatMessage(date, new File(audio));
                }

                userMessages.add(cm);

            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();

        return userMessages;
    }

    ArrayList<ChatMessage> retrieveReceivedMessages(String userId) {
        Log.d(TAG, "alınan mesajları alma");
        return retrieveMessages(userId, RECEIVED_MESSAGES_TABLE);
    }

    ArrayList<ChatMessage> retrieveSentMessages(String userId) {
        Log.d(TAG, "gönderilen mesajları alma");
        return retrieveMessages(userId, SENT_MESSAGES_TABLE);
    }

    void clearAll() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("DROP TABLE IF EXISTS " + RECEIVED_MESSAGES_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + SENT_MESSAGES_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + USER_NAMES_TABLE);
        onCreate(db);
        db.close();
    }

    // Gönderdiğiniz ve aldığınız mesajları sıralar ve birleştirir
    static List<ChatMessage> combineMessages(List<ChatMessage> readMessages,
                                             List<ChatMessage> sentMessages) {
        List<ChatMessage> combined = new ArrayList<>();

        for (ChatMessage message : readMessages) {
            combined.add(message);
        }

        for (ChatMessage message : sentMessages) {
            combined.add(message);
        }

        Collections.sort(combined, new Comparator<ChatMessage>() {
            @Override
            public int compare(ChatMessage message1, ChatMessage message2) {
                return message1.timeStamp.compareTo(message2.timeStamp);
            }
        });

        return combined;
    }

    List<String> getPreviousChatNames() {
        List<String> userNames = new ArrayList<>();
        String select = "SELECT " + USER_ID + ", " + USER_NAME + " FROM " + USER_NAMES_TABLE;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(select, null);

        if (cursor.moveToFirst()) {
            do {
                String macAddress = cursor.getString(0);
                String userName = cursor.getString(1);

                userNames.add(userName + "\n" + macAddress);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return userNames;
    }

    static byte[] compressBitmap(Bitmap image, boolean isBeforeSocketSend) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        image.compress(Bitmap.CompressFormat.JPEG, 50, bos);
        String encodedImage = Base64.encodeToString(bos.toByteArray(),
                Base64.DEFAULT);

        byte[] compressed = isBeforeSocketSend ? encodedImage.getBytes()
                : Base64.decode(encodedImage, Base64.DEFAULT);

        return compressed;
    }

    // mac adresi verilen cihaz ile konuşmayı sil
    public void deleteFullConversation(String user_id){
        deleteMessages(user_id, RECEIVED_MESSAGES_TABLE);
        deleteMessages(user_id, SENT_MESSAGES_TABLE);
        deleteConversation(user_id);
    }

    public void deleteConversation(String user_id){
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(USER_NAMES_TABLE, USER_ID + " = ?", new String[]{user_id});
        db.close();
    }

    public void deleteMessages(String user_id, String table){
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(RECEIVED_MESSAGES_TABLE, USER_ID + " = ?", new String[]{user_id});
        db.close();
    }
}
