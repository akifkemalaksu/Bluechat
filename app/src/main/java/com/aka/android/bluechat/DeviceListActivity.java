package com.aka.android.bluechat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;


import java.util.Set;

public class DeviceListActivity extends Activity {

    private BluetoothAdapter mBtAdapter;
    public static String EXTRA_DEVICE_ADDRESS = "device_address";
    private ArrayAdapter<String> mNewDeviceArrayAdapter;
    int MY_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 1;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.bluetooth_device_list);
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();

        discoverDevices();

        mNewDeviceArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);

        ListView newDeviceListView = findViewById(R.id.new_devices);
        newDeviceListView.setAdapter(mNewDeviceArrayAdapter);
        newDeviceListView.setOnItemClickListener(mDeviceClickListener);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(mReceiver, filter);

        Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                mNewDeviceArrayAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        }

    }

    private void discoverDevices() {
        setTitle("Cihazlar Taranıyor...");

        // Bluetooth cihazlarını zaten tarıyorsak durdurun
        if (mBtAdapter.isDiscovering()) {
            mBtAdapter.cancelDiscovery();
        }

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                MY_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);

        // Bluetooth adaptöründen cihaz taraması iste
        mBtAdapter.startDiscovery();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    mNewDeviceArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setTitle(R.string.select_device);
                if (mNewDeviceArrayAdapter.getCount() == 0) {
                    String noDevices = getResources().getText(R.string.none_found).toString();
                    mNewDeviceArrayAdapter.add(noDevices);
                }
            }
        }
    };


    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {

            mBtAdapter.cancelDiscovery();


            String info = ((TextView)view).getText().toString();
            String address = info.substring(info.length() - 17);

            // mac adresi bulunan bir result intent oluştur
            Intent intent = new Intent();
            intent.putExtra(EXTRA_DEVICE_ADDRESS,address);

            // resultu ayarla ve bu activity i bitir
            setResult(Activity.RESULT_OK,intent);
            finish();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mBtAdapter != null) {
            mBtAdapter.cancelDiscovery();
        }

        // Unregister broadcast
        this.unregisterReceiver(mReceiver);
    }

}
