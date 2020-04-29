package com.example.arduino_bluetooth_car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // car state

    // gas can be value from 0 to 99 it is transmitted in 2 digits
    private int gas_state = 0;
    private static int gas_max = 100;


    // turn can vary from 0 to 180 to be compatible with servo
    private int turn_state = 90;
    private static int turn_min = 20;
    private static int turn_max = 160;

    private ListView listBluetoothDevices;

    // bluetooth
    private static final int REQUEST_ENABLE_BT = 1;
    private boolean bluetoothEnabled = false;
    private BluetoothAdapter bluetoothAdapter;
    private List<BluetoothDevice> bluetoothDevicesList = new ArrayList<>();

    private BluetoothSocket btSocket = null;
    private ConnectedThread connectedThread = null;
    private Handler bluetoothIn;
    private int replyMessageCode = 0;

    private Handler continuous_sender = new Handler();

    private Runnable send_currend_state_slow = new Runnable(){
        @Override
        public void run() {
            sendMessage(turn_state, gas_state);
            continuous_sender.postDelayed(send_currend_state_slow, 400);
        }
    };

    private Runnable send_currend_state_fast = new Runnable(){
        @Override
        public void run() {
            sendMessage(turn_state, gas_state);
            continuous_sender.postDelayed(send_currend_state_fast, 50);
        }
    };

    private Runnable establish_connection = new Runnable() {
        @Override
        public void run() {
            if (connectedThread != null)
                connectedThread.write(new byte[]{(byte)1, (byte)2, (byte)3});
            continuous_sender.postDelayed(establish_connection, 100);
        }
    };

    @SuppressLint({"HandlerLeak", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View main_view = LayoutInflater.from(this).inflate(R.layout.activity_main, null);

        SeekBar turn_slider = main_view.findViewById(R.id.turn_slider);
        SeekBar gas_slider = main_view.findViewById(R.id.gas_slider);
        TextView turn_text = main_view.findViewById(R.id.turn_text);
        TextView gas_text = main_view.findViewById(R.id.gas_text);

        turn_slider.setMin(turn_min);
        turn_slider.setMax(turn_max);
        turn_slider.setProgress((turn_max+turn_min)/2);
        turn_text.setText(getString(R.string.turn_label, turn_slider.getProgress()));
        turn_slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                turn_state = progress;
                turn_text.setText(getString(R.string.turn_label, progress));

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                continuous_sender.removeCallbacks(send_currend_state_slow);
                continuous_sender.post(send_currend_state_fast);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                continuous_sender.removeCallbacks(send_currend_state_fast);
                continuous_sender.post(send_currend_state_slow);
            }
        });

        gas_slider.setMin(0);
        gas_slider.setMax(gas_max);
        gas_slider.setProgress(0);
        gas_text.setText(getString(R.string.gas_label, gas_slider.getProgress()));
        gas_slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gas_state = progress;
                gas_text.setText(getString(R.string.gas_label, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                continuous_sender.removeCallbacks(send_currend_state_fast);
                continuous_sender.removeCallbacks(send_currend_state_slow);
                continuous_sender.post(send_currend_state_fast);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                continuous_sender.removeCallbacks(send_currend_state_slow);
                continuous_sender.removeCallbacks(send_currend_state_fast);
                continuous_sender.post(send_currend_state_slow);
            }
        });

        Button btn_stop_all = main_view.findViewById(R.id.btn_stop_all);

        EditText gas_field = main_view.findViewById(R.id.gas_field);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        Button btnEnableBluetooth = main_view.findViewById(R.id.btn_enable_bluetooth);
        listBluetoothDevices = main_view.findViewById(R.id.list_view_blt_devices);

        // control_buttons
//        btn_gas.setOnTouchListener(new ContinuousClick(() -> handle_gas(true)));
//        btn_break.setOnTouchListener(new ContinuousClick(() -> handle_gas(false)));

        gas_field.setText(String.valueOf(gas_max));
        gas_field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                try{
                    gas_max = Integer.valueOf(s.toString());
                    gas_slider.setMax(gas_max);
                }catch (Exception e) {
                    gas_max = 0;
                }
            }
        });

        btn_stop_all.setOnClickListener(v -> {
                    turn_state = 90;
                    gas_state = 0;

                    turn_slider.setProgress(turn_state);
                    gas_slider.setProgress(gas_state);

                    sendMessage(turn_state, gas_state);
                });

        btnEnableBluetooth.setOnClickListener(v -> enableBluetooth());

        listBluetoothDevices.setOnItemClickListener((parent, view, position, id) -> connectToDevice(bluetoothDevicesList.get(position)));

        bluetoothIn = new Handler(){
            @Override
            public void handleMessage(@NonNull Message msg) {
                //handle
                if(msg.what == replyMessageCode){
                    Toast.makeText(MainActivity.this, "Setup complete " + msg.toString(), Toast.LENGTH_SHORT).show();
                    continuous_sender.removeCallbacks(establish_connection);
                    continuous_sender.removeCallbacks(send_currend_state_slow);
                    continuous_sender.removeCallbacks(send_currend_state_fast);
                    continuous_sender.post(send_currend_state_slow);
                }
            }
        };
        enableBluetooth();
        setContentView(main_view);
    }

    private void connectToDevice(BluetoothDevice device) {
        try {
                if (btSocket == null){
                    btSocket = createBluetoothSocket(device);
                }else if (btSocket.isConnected()) {
                    continuous_sender.removeCallbacks(establish_connection);
                    continuous_sender.removeCallbacks(send_currend_state_slow);
                    continuous_sender.removeCallbacks(send_currend_state_fast);
                    btSocket.close();
                    Toast.makeText(getBaseContext(), "Socket disconnected", Toast.LENGTH_LONG).show();
                    return;
                }else{
                    btSocket = createBluetoothSocket(device);
                }
            } catch (IOException e) {
            Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_LONG).show();
            return;
        }
        // Establish the Bluetooth socket connection.
        try{
            btSocket.connect();
            Toast.makeText(getBaseContext(), "Socket connected", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(getBaseContext(), "Socket connect failed", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }

        connectedThread = new ConnectedThread(btSocket);
        connectedThread.start();

        continuous_sender.removeCallbacks(send_currend_state_slow);
        continuous_sender.removeCallbacks(send_currend_state_fast);
        continuous_sender.post(establish_connection);
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException{
        return  device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
    }

    private void enableBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device doesn't support Bluetooth", Toast.LENGTH_LONG).show();
            bluetoothEnabled = false;
        }else{
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }else{
                bluetoothEnabled = true;
                refreshDevicesList();
            }
        }
    }

    private void refreshDevicesList(){
        if(bluetoothEnabled) {
            bluetoothDevicesList = new ArrayList<>();
            bluetoothDevicesList.addAll(bluetoothAdapter.getBondedDevices());

            List<String> deviceNames = new ArrayList<>();

            if (bluetoothDevicesList.size() > 0) {
                // There are paired devices. Get the name and address of each paired device.
                for (BluetoothDevice device : bluetoothDevicesList) {
                    String deviceName = device.getName();
                    String deviceHardwareAddress = device.getAddress(); // MAC address
                    deviceNames.add(deviceName + "  " + deviceHardwareAddress);
                }
            }

            ArrayAdapter<String> adapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames);
            listBluetoothDevices.setAdapter(adapter);
        }else{
            Toast.makeText(this, "Bluetooth disabled", Toast.LENGTH_SHORT).show();
        }
    }

    // bluetooth
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if(requestCode == REQUEST_ENABLE_BT){
            if(resultCode == RESULT_OK){
                bluetoothEnabled = true;
                refreshDevicesList();
            }else if(resultCode == RESULT_CANCELED){
                bluetoothEnabled = false;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // turn 0 -> 180
    // gas 0 -> 99
    public void sendMessage(int turn, int gas){
        if (connectedThread != null)
            connectedThread.write(new byte[]{(byte)turn, (byte)gas, (byte)0});
    }

    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        //creation of the connect thread
        public ConnectedThread(BluetoothSocket socket) {
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                //Create I/O streams for connection
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[256];
            int bytes;


            // Keep looping to listen for received messages
            while (true) {
                try {
                    bytes = mmInStream.read(buffer);            //read bytes from input buffer
                    String readMessage = new String(buffer, 0, bytes);
                    // Send the obtained bytes to the UI Activity via handler
                    bluetoothIn.obtainMessage(replyMessageCode, bytes, -1, readMessage).sendToTarget();
                } catch (IOException e) {
                    break;
                }
            }
        }
        //write method
        public void write(byte[] msgBuffer) {
            try {
                mmOutStream.write(msgBuffer);                //write bytes over BT connection via outstream
            } catch (IOException e) {
                //if you cannot write, close the sending task
                Toast.makeText(getBaseContext(), "Data send failed", Toast.LENGTH_LONG).show();
                continuous_sender.removeCallbacks(establish_connection);
                continuous_sender.removeCallbacks(send_currend_state_slow);
                continuous_sender.removeCallbacks(send_currend_state_fast);
            }
        }
    }
}
