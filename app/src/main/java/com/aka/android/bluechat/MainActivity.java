package com.aka.android.bluechat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    ChatMessages db;

    private ListView chatHistoryListView;
    ArrayAdapter<String> adapter;
    ArrayList<String> previousChatNames;
    public static BluetoothAdapter mBluetoothAdapter = null;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int MY_PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE = 2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            mBluetoothAdapter.enable();

            ActivityCompat.requestPermissions(this, new String[]
                            {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MY_PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE);
        }

        db = new ChatMessages(getApplicationContext());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        chatHistoryListView = findViewById(R.id.list_chat_history);

        final FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                System.out.println(mBluetoothAdapter.enable());
                Intent newChatIntent = new Intent(getApplicationContext(), ChatActivity.class);
                startActivity(newChatIntent);
            }
        });

        chatHistoryListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position,
                                    long id) {
                Intent intent = new Intent(getApplicationContext(), ChatActivity.class);
                String user = chatHistoryListView.getItemAtPosition(position).toString();
                UserInfo usersInfo = UserInfo.getUserInfo(user);
                intent.putExtra("USERS-INFO", usersInfo);
                startActivity(intent);
            }
        });

        chatHistoryListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long l) {

                String user = chatHistoryListView.getItemAtPosition(position).toString();
                String user_id = UserInfo.getUserInfo(user).macAddress;
                db.deleteFullConversation(user_id);

                Toast.makeText(MainActivity.this, R.string.deleted_selected_conversation_message, Toast.LENGTH_SHORT).show();

                loadConversations();

                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        previousChatNames = (ArrayList<String>) db.getPreviousChatNames();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, previousChatNames);
        chatHistoryListView.setAdapter(adapter);
    }

    public void loadConversations(){
        previousChatNames = (ArrayList<String>) db.getPreviousChatNames();
        adapter.clear();
        adapter.addAll(previousChatNames);
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_delete_all) {
            deleteAll();
            Toast.makeText(this, R.string.deleted_all_conversation_message, Toast.LENGTH_SHORT).show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void deleteAll() {
        db = new ChatMessages(getApplicationContext());
        db.clearAll();
        loadConversations();
    }
}
