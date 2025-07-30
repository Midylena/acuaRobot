package com.example.acuarobot;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.*;
import android.widget.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    Button btnScan, btnSend;
    ListView listDevices;
    EditText edtMessage;
    TextView txtStatus, txtDirecao;

    BluetoothAdapter bluetoothAdapter;
    ArrayAdapter<String> devicesArrayAdapter;
    List<BluetoothDevice> devicesList = new ArrayList<>();

    BluetoothSocket bluetoothSocket;
    OutputStream outputStream;
    InputStream inputStream;

    final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Volante (acelerômetro)
    SensorManager sensorManager;
    Sensor accSensor;
    SensorEventListener accListener;
    String lastDirection = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnScan = findViewById(R.id.btnScan);
        listDevices = findViewById(R.id.listDevices);
        edtMessage = findViewById(R.id.edtMessage);
        btnSend = findViewById(R.id.btnSend);
        txtStatus = findViewById(R.id.txtStatus);
        txtDirecao = findViewById(R.id.txtDirecao);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        devicesArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listDevices.setAdapter(devicesArrayAdapter);

        btnScan.setOnClickListener(view -> scanDevices());
        listDevices.setOnItemClickListener((parent, view, position, id) -> connectToDevice(devicesList.get(position)));
        btnSend.setOnClickListener(view -> sendMessage(edtMessage.getText().toString()));

        // Permissões (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 1);
            }
        }

        btnSend.setEnabled(false);
        edtMessage.setEnabled(false);

        // Inicializa o acelerômetro para modo volante
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        accListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float x = event.values[0]; // esquerda/direita
                float y = event.values[1]; // frente/trás
                String direcao = "PARADO";
                String comando = "";

                if (y < -4) {
                    direcao = "FRENTE";
                    comando = "F";
                } else if (y > 4) {
                    direcao = "TRÁS";
                    comando = "B";
                } else if (x > 4) {
                    direcao = "ESQUERDA";
                    comando = "L";
                } else if (x < -4) {
                    direcao = "DIREITA";
                    comando = "R";
                }

                if (!direcao.equals(lastDirection)) {
                    txtDirecao.setText("Direção: " + direcao);
                    lastDirection = direcao;

                    if (outputStream != null) {
                        String toSend;
                        if (!comando.equals("")) {
                            toSend = comando + "\n";
                        } else {
                            toSend = "0\n";
                        }
                        try {
                            outputStream.write(toSend.getBytes());
                        } catch (IOException e) {
                        }
                    }
                }

            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        if (accSensor != null) {
            sensorManager.registerListener(accListener, accSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            txtDirecao.setText("Acelerômetro não disponível.");
        }
    }

    void scanDevices() {
        devicesArrayAdapter.clear();
        devicesList.clear();
        txtStatus.setText("Escaneando...");

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            devicesArrayAdapter.add("Pareado: " + device.getName() + "\n" + device.getAddress());
            devicesList.add(device);
        }

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && !devicesList.contains(device)) {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            // TODO: Consider calling
                            //    ActivityCompat#requestPermissions
                            // here to request the missing permissions, and then overriding
                            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                            //                                          int[] grantResults)
                            // to handle the case where the user grants the permission. See the documentation
                            // for ActivityCompat#requestPermissions for more details.
                            return;
                        }
                        devicesArrayAdapter.add("Encontrado: " + device.getName() + "\n" + device.getAddress());
                        devicesList.add(device);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        bluetoothAdapter.startDiscovery();

        new Handler().postDelayed(() -> {
            bluetoothAdapter.cancelDiscovery();
            txtStatus.setText("Escaneamento finalizado.");
            unregisterReceiver(receiver);
        }, 12000);
    }

    void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        txtStatus.setText("Conectando a: " + device.getName());

        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();

                runOnUiThread(() -> {
                    txtStatus.setText("Conectado a: " + device.getName());
                    btnSend.setEnabled(true);
                    edtMessage.setEnabled(true);
                });

                listenForData();

            } catch (IOException e) {
                runOnUiThread(() -> txtStatus.setText("Falha ao conectar!"));
                e.printStackTrace();
            }
        }).start();
    }

    void sendMessage(String message) {
        if (outputStream != null && message.length() > 0) {
            try {
                outputStream.write(message.getBytes());
                txtStatus.setText("Mensagem enviada!");
                edtMessage.setText("");
            } catch (IOException e) {
                txtStatus.setText("Erro ao enviar!");
                e.printStackTrace();
            }
        } else {
            txtStatus.setText("Não conectado ou mensagem vazia!");
        }
    }

    void listenForData() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            int bytes;
            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    final String received = new String(buffer, 0, bytes);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Recebido: " + received, Toast.LENGTH_SHORT).show());
                } catch (IOException e) {
                    break;
                }
            }
        }).start();
    }
}