import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;


public class Main {
    public static byte[] serialize(Object object) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(object);
            objectOutputStream.close();
            byteArrayOutputStream.close();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Payload deserializePayload(byte[] byteArray) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
             ObjectInputStream ois = new ObjectInputStream(bais)) {

            return (Payload) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Dev_props_switch_who_i_am deserializeDevPropsSwitch(byte[] byteArray) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
             ObjectInputStream ois = new ObjectInputStream(bais)) {

            return (Dev_props_switch_who_i_am) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Cmd_body_who_is_where_i_am_here deserializeCmdBodySensor(byte[] byteArray) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
             ObjectInputStream ois = new ObjectInputStream(bais)) {

            return (Cmd_body_who_is_where_i_am_here) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Dev_props_sensor_who_i_am deserializeDevPropsSensor(byte[] byteArray) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
             ObjectInputStream ois = new ObjectInputStream(bais)) {

            return (Dev_props_sensor_who_i_am) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static int[] decodeByteArrayToIntArray(byte[] byteArray) {
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        int[] intArray = new int[byteArray.length / 4];

        for (int i = 0; i < intArray.length; i++) {
            intArray[i] = buffer.getInt();
        }

        return intArray;
    }


    static int timeNow = 0;
    static int hubNum = 0;
    static int sensorNum = 0;
    static int lampNum = 0;
    static int switchNum = 0;
    static int socketNum = 0;
    static Clock clock;
    static Hub hub;

    private static String addPaddingToBase64(String base64) {
        int paddingLength = 4 - (base64.length() % 4);
        return base64 + "=".repeat(paddingLength);
    }

    public static void main(String[] args){
        List<On_Off_Device> deviceList = new ArrayList<>();
        HttpURLConnection connection = null;
        String serverURL = args[0];
        String hubHex = args[1];
        int hubAddress = Integer.parseInt(hubHex, 16);
        try {
            connection = (HttpURLConnection) new URL(serverURL).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            hub = new Hub();
            hub.name = "hub";
            hub.address = hubAddress;

            // ЗАПРОС WHOISHERE ОТ ХАБА
            String message = WHOISHEREIAMHERE(hub, deviceList, true);


            // Получение потока вывода для записи данных в соединение
            String response = getResponse(message, connection);


            // ДЕКОДИРОВАНИЕ ПОЛУЧЕННЫХ ПАКЕТОВ ПОСЛЕ ЗАПРОСА WHOISHERE


            List<Packet> responsePackets = new ArrayList<>();
            decodePackets(response, responsePackets);
            String name;
            List<Packet> switchPackets = new ArrayList<>();
            for (Packet responsePacket : responsePackets) {
                Payload payload = deserializePayload(responsePacket.getPayload());
                if (payload == null) {
                    return;
                }
                if (payload.cmd == 2) {
                    addDeviceNotSwitch(payload, deviceList, switchPackets, responsePackets);
                }
                if (payload.cmd == 6) {
                    timeNow = ByteBuffer.wrap(payload.cmd_body).getInt();

                    //проверка времени


                }
            }
            addSwitch(switchPackets, deviceList);

            // ЗАПРОСЫ GETSTATUS НА ВСЕ ДОСТУПНЫЕ УСТРОЙСТВА

            for (On_Off_Device dev : deviceList) {
                if (dev.type != 6) {
                    responsePackets = new ArrayList<>();
                    response = getResponse(GETSTATUS(dev), connection);
                    if (response == null) {
                        dev.canGetQuery = false;
                    } else {
                        dev.hadGetStatusQuery = true;
                        decodePackets(response, responsePackets);
                        decodeStatus(responsePackets, dev, connection, deviceList);
                    }
                }
            }

            // Прослушивание входяших пост запросов
            try {
                // Настройка порта сервера
                int port = 9998;

                // Создание серверного сокета
                ServerSocket serverSocket = new ServerSocket(port);


                while (true) {
                    // Прием входящего соединения
                    java.net.Socket clientSocket = serverSocket.accept();

                    // Обработка входящего соединения в отдельном потоке
                    HttpURLConnection finalConnection = connection;
                    Thread thread = new Thread(() -> {
                        try {
                            // Чтение данных из входного потока
                            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                            String line;
                            StringBuilder request = new StringBuilder();
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                request.append(line).append("\r\n");
                            }

                            String query = request.toString();


                            List<Packet> packets = new ArrayList<>();
                            decodePackets(query, packets);
                            Payload devPayload = deserializePayload(packets.get(0).getPayload());
                            if (devPayload == null) {
                                System.exit(99);
                            }
                            if (devPayload.cmd == 4) {
                                // изменение статуса  свича
                                if (devPayload.getDev_type() == 2) {       // если статус отправил сенсор(достигнуто значение триггера)
                                    Sensor sens = null;
                                    for (On_Off_Device dev : deviceList) {
                                        if (dev.address == devPayload.getSrc()) {
                                            sens = (Sensor) dev;
                                            break;
                                        }
                                    }
                                    if (sens != null) {
                                        if (sens.canGetQuery) {
                                            decodeStatus(packets, sens, finalConnection, deviceList);
                                        }
                                    }
                                }
                                if (devPayload.getDev_type() == 3) {         // если статус отправил переключатель(надо изменить состояние розеток и ламп)
                                    Switch sw = null;
                                    for (On_Off_Device dev : deviceList) {
                                        if (dev.address == devPayload.getSrc()) {
                                            sw = (Switch) dev;
                                            break;
                                        }
                                    }
                                    if (sw != null) {
                                        if (sw.canGetQuery) {
                                            decodeStatus(packets, sw, finalConnection, deviceList);
                                        }
                                    }
                                }

                            }
                            if (devPayload.cmd == 1) {

                                deviceList.removeIf(dev -> dev.address == devPayload.getSrc());
                                List<Packet> packetsSwitch = new ArrayList<>();

                                addDeviceNotSwitch(devPayload, deviceList, packetsSwitch, packets);
                                addSwitch(packetsSwitch, deviceList);
                                String iAmHereQuery = WHOISHEREIAMHERE(hub, deviceList, false); // ответ хаба
                                getResponse(iAmHereQuery, finalConnection);
                                iAmHereQuery = WHOISHEREIAMHERE(clock, deviceList, false); // ответ таймера
                                getResponse(iAmHereQuery, finalConnection);
                                for (On_Off_Device dev : deviceList) {                                     // ответы всех остальных устройств
                                    if (dev.canGetQuery){
                                        iAmHereQuery = WHOISHEREIAMHERE(dev, deviceList, false);
                                        if (getResponse(iAmHereQuery, finalConnection) != null) {
                                            dev.canGetQuery = true;
                                        }
                                    }

                                }
                            }
                            if (devPayload.cmd == 6) {
                                timeNow = ByteBuffer.wrap(devPayload.cmd_body).getInt();
                            }

                            reader.close();
                            clientSocket.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    thread.start();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }


            // Закрытие соединения
            connection.disconnect();

        } catch (Throwable cause) {
            cause.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

    }


    private static void decodeStatus(List<Packet> responsePackets, On_Off_Device dev, HttpURLConnection connection,
                                     List<On_Off_Device> deviceList) throws IOException {
        for (Packet pack : responsePackets) {
            Payload payload = deserializePayload(pack.getPayload());
            if (payload == null) {
                return;
            }
            if (payload.cmd == 6) {
                timeNow = ByteBuffer.wrap(payload.cmd_body).getInt();
            }
            if (payload.cmd == 4) {
                int[] val = decodeByteArrayToIntArray(payload.cmd_body);
                dev.values.setValues(val);
                if (payload.getDev_type() == 3) {       // если статус отправил переключатель, надо на всех лампочках и розетках выставить это значение
                    for (On_Off_Device device : deviceList) {
                        if (device.type == 4 || device.type == 5) {
                            if (dev.hadGetStatusQuery) {    // если на это устройство ранее приходил гетстатус то оно отправляет сетстатус
                                getResponse(SETSTATUS(device, val[0]), connection);
                            }
                        }
                    }
                }
                if (payload.getDev_type() == 2) {
                    checkTriggers(connection, val, payload.getSrc(), deviceList);

                }
            }
        }

    }

    private static void addDeviceNotSwitch(Payload devPayload, List<On_Off_Device> deviceList, List<Packet> packetsSwitch
            , List<Packet> packets) {
        // дбоавление устройства написать
        String newName;
        switch (devPayload.getDev_type()) {
            case 2:
                Cmd_body_who_is_where_i_am_here cmbwhiamh = deserializeCmdBodySensor(devPayload.cmd_body);
                newName = cmbwhiamh.getDev_name();
                byte[] dev_props = cmbwhiamh.getDev_props();
                Dev_props_sensor_who_i_am dps = deserializeDevPropsSensor(dev_props);
                byte sensors = dps.getSensors();
                Trigger[] triggers = dps.getTriggers();
                Sensor newSensor = new Sensor(deviceList);
                newSensor.triggers = triggers;
                newSensor.type = 2;
                newSensor.address = devPayload.getSrc();
                newSensor.name = newName;
                newSensor.sensors = sensors;
                break;
            case 3:
                packetsSwitch.add(packets.get(0));
                break;

            case 4:
                newName = new String(devPayload.cmd_body, StandardCharsets.UTF_8);
                Lamp newLamp = new Lamp(deviceList);
                newLamp.type = 4;
                newLamp.name = newName;
                newLamp.address = devPayload.getSrc();
                break;
            case 5:
                newName = new String(devPayload.cmd_body, StandardCharsets.UTF_8);
                SOcket newSocket = new SOcket(deviceList);
                newSocket.type = 5;
                newSocket.name = newName;
                newSocket.address = devPayload.getSrc();
                break;

        }
    }


    private static void addSwitch(List<Packet> packetsSwitch, List<On_Off_Device> deviceList) {
        for (Packet p : packetsSwitch) {
            Payload payload = deserializePayload(p.getPayload());
            Switch newSwitch = new Switch(deviceList);
            byte[] cmdBody = payload.cmd_body;
            Cmd_body_who_is_where_i_am_here cmh = deserializeCmdBodySensor(cmdBody);
            newSwitch.name = cmh.getDev_name();
            byte[] devProps = cmh.getDev_props();
            Dev_props_switch_who_i_am dpswh = deserializeDevPropsSwitch(devProps);
            newSwitch.neighbours = dpswh.getDevice_names();
            newSwitch.type = 3;
            newSwitch.address = payload.getSrc();
        }
    }

    private static String getBinaryString(byte number) {
        StringBuilder binary = new StringBuilder();
        for (int bit = 7; bit >= 0; bit--) {
            binary.append((number >> bit) & 1);
        }
        return binary.toString();
    }

    private static void checkTriggers(HttpURLConnection connection, int[] values, int sensorAddress,
                                      List<On_Off_Device> deviceList) throws IOException {
        Sensor currentSensor = null;
        for (On_Off_Device dev : deviceList) {
            if (dev.address == sensorAddress) {
                currentSensor = (Sensor) dev;
                break;
            }
        }

        if (currentSensor != null) {
            String sensorsString = getBinaryString(currentSensor.sensors);

            for (int i = 0; i < currentSensor.triggers.length; ++i) {
                String op = getBinaryString(currentSensor.triggers[i].getOp());
                int num_of_sensor = op.charAt(4) * 2 + op.charAt(5);
                int index_in_values = 0;
                for (int g = sensorsString.length() - 1; g > 7 - num_of_sensor; --g) {
                    if (sensorsString.charAt(g) == '1') {
                        index_in_values++;
                    }
                }
                if (op.charAt(6) == '1') {
                    if (values[index_in_values] > currentSensor.triggers[i].getValue()) {
                        setValueOfDevice(connection, deviceList, op, currentSensor.triggers[i].getName());
                        return;
                    }
                } else {
                    if (values[index_in_values] < currentSensor.triggers[i].getValue()) {
                        setValueOfDevice(connection, deviceList, op, currentSensor.triggers[i].getName());
                        return;
                    }
                }
            }
        }
    }

    private static void setValueOfDevice(HttpURLConnection connection, List<On_Off_Device> deviceList, String op,
                                         String triggerName) throws IOException {
        for (On_Off_Device dev : deviceList) {
            if (dev.name.equals(triggerName)) {
                int valueToSet;
                if (op.charAt(7) == '1') {
                    valueToSet = 1;
                } else {
                    valueToSet = 0;
                }
                dev.values.getValues()[0] = valueToSet;
                String query = SETSTATUS(dev, valueToSet);
                getResponse(query, connection);
            }
        }
    }


    private static void decodePackets(String response, List<Packet> responsePackets) {
        String decodedString = java.net.URLDecoder.decode(response, StandardCharsets.UTF_8);

        // Декодирование непрописанной (unpadded) base64-строки
        String paddedBase64 = addPaddingToBase64(decodedString);
        byte[] base64Decoded = Base64.getUrlDecoder().decode(paddedBase64);

        // Десериализация объектов класса
        try (ByteArrayInputStream bais = new ByteArrayInputStream(base64Decoded);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            while (bais.available() > 0) {
                Packet obj = (Packet) ois.readObject();
                responsePackets.add(obj);
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private static String getResponse(String message, HttpURLConnection connection) throws IOException {
        OutputStream outputStream = connection.getOutputStream();
        outputStream.write(message.getBytes());
        outputStream.flush();
        connection.setReadTimeout(300);
        connection.setConnectTimeout(300);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode == 204) {
                System.exit(0);
            }
            if (responseCode != 200) {
                System.exit(99);
            }

            // Чтение ответа от сервера
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            StringBuilder response = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            return response.toString();
        } catch (SocketTimeoutException e) {
            return null;
        }


    }

    private static String SETSTATUS(On_Off_Device deviceReceiver, int value) {
        Packet packet = new Packet();
        Payload payload = new Payload();
        payload.setSrc(hub.address);
        payload.setDst(deviceReceiver.address);
        payload.setSerial(++hubNum);
        packet.setLength((byte) 6);
        payload.setDev_type(deviceReceiver.type);
        payload.setCmd((byte) 5);
        payload.setCmd_body(new byte[]{(byte) value});
        deviceReceiver.values.setValues(new int[]{value});
        return encodePacket(payload, packet);

    }

    private static String GETSTATUS(Device deviceReceiver) {
        Packet packet = new Packet();
        Payload payload = new Payload();
        payload.setSrc(hub.address);
        payload.setDst(deviceReceiver.address);
        payload.setSerial(++hubNum);
        packet.setLength((byte) 5);
        payload.setDev_type(deviceReceiver.type);
        payload.setCmd((byte) 3);
        return encodePacket(payload, packet);
    }

    private static String WHOISHEREIAMHERE(Device device, List<On_Off_Device> list, boolean is_who_packet) {
        Packet packet = new Packet();
        Payload payload = new Payload();
        payload.setSrc(device.address);
        payload.setDst(16383);
        switch (device.type) {
            case 1:
                payload.setSerial(++hubNum);
                packet.setLength((byte) 12);
                break;
            case 2:
                payload.setSerial(++sensorNum);
                packet.setLength((byte) 56);
                break;

            case 3:
                payload.setSerial(++switchNum);
                packet.setLength((byte) 34);
                break;

            case 4:
                payload.setSerial(++lampNum);
                packet.setLength((byte) 13);
                break;

            case 5:
                payload.setSerial(++socketNum);
                packet.setLength((byte) 15);
                break;

        }
        byte[] cmd_body = new byte[0];
        switch (device.type) {
            case 1, 4, 5:
                Cmd_body_hub_who cmb = new Cmd_body_hub_who();
                cmb.setDev_name(device.name);
                cmd_body = serialize(cmb);

                break;
            case 2:
                break;
            case 3:
                Cmd_body_who_is_where_i_am_here cmbd = new Cmd_body_who_is_where_i_am_here();
                cmbd.setDev_name(device.name);
                cmbd.setDev_props(createDevPropsSwitch(list));
                cmd_body = serialize(cmbd);
                break;

        }
        payload.setCmd_body(cmd_body);
        payload.setDev_type(device.type);
        payload.setCmd(is_who_packet ? (byte) 1 : 2);
        return encodePacket(payload, packet);


    }

    private static String encodePacket(Payload payload, Packet packet) {
        byte[] dataBytes = serialize(payload);
        packet.setPayload(dataBytes);
        packet.setCrc8(compute_CRC8_Simple(dataBytes));
        byte[] bytesToEncodeBase64 = serialize(packet);
        String base64EncodedString = Base64.getUrlEncoder().withoutPadding().encodeToString(bytesToEncodeBase64);
        return URLEncoder.encode(base64EncodedString, StandardCharsets.UTF_8);

    }

    private static byte[] createDevPropsSwitch(List<On_Off_Device> list) {
        List<String> deviceNames = new ArrayList<>();
        for (Device device : list) {
            if (device.type == 4 || device.type == 5) {
                deviceNames.add(device.name);
            }
        }
        String[] names = new String[deviceNames.size()];
        for (int i = 0; i < deviceNames.size(); ++i) {
            names[i] = deviceNames.get(i);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        for (String str : names) {
            byte[] encodedBytes = str.getBytes(StandardCharsets.UTF_8);
            outputStream.write(encodedBytes, 0, encodedBytes.length);
        }

        return outputStream.toByteArray();

    }

    private static byte compute_CRC8_Simple(byte[] bytes) {
        final byte generator = 0x1D;
        byte crc = 0;

        for (byte currByte : bytes) {
            crc ^= currByte;

            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (byte) ((crc << 1) ^ generator);
                } else {
                    crc <<= 1;
                }
            }
        }

        return crc;
    }


}

abstract class Device implements Serializable {
    byte type;
    String name;
    int address;
    boolean canGetQuery;

}

class Hub extends Device {

    public Hub() {
        type = 1;
    }

}

abstract class On_Off_Device extends Device {
    Cmd_body_sensor_status values;
    boolean hadGetStatusQuery = false;

}

class Sensor extends On_Off_Device {

    byte sensors;
    Trigger[] triggers;

    public Sensor(List<On_Off_Device> list) {
        type = 2;
        list.add(this);
        canGetQuery = true;
    }
}

class Switch extends On_Off_Device {

    String[] neighbours;

    public Switch(List<On_Off_Device> list) {
        type = 3;
        values = new Cmd_body_sensor_status();
        values.setValues(new int[1]);
        list.add(this);
        canGetQuery = true;

    }
}

class Lamp extends On_Off_Device {

    public Lamp(List<On_Off_Device> list) {
        type = 4;
        values = new Cmd_body_sensor_status();
        values.setValues(new int[1]);
        list.add(this);
        canGetQuery = true;

    }
}

class SOcket extends On_Off_Device {


    public SOcket(List<On_Off_Device> list) {
        type = 5;
        values = new Cmd_body_sensor_status();
        values.setValues(new int[1]);
        list.add(this);
        canGetQuery = true;

    }
}

class Clock extends Device {
    Cmd_body_timer cmt;

    public Clock() {
        type = 6;
    }

}


class Packet implements Serializable {
    private byte length;
    private byte[] payload;
    private byte crc8;

    public byte getLength() {
        return length;
    }

    public void setLength(byte length) {
        this.length = length;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public byte getCrc8() {
        return crc8;
    }

    public void setCrc8(byte crc8) {
        this.crc8 = crc8;
    }
}

class Payload implements Serializable {
    private int src;
    private int dst;
    private int serial;
    private byte dev_type;
    public byte cmd;
    public byte[] cmd_body;

    public int getSrc() {
        return src;
    }

    public void setSrc(int src) {
        this.src = src;
    }

    public int getDst() {
        return dst;
    }

    public void setDst(int dst) {
        this.dst = dst;
    }

    public int getSerial() {
        return serial;
    }

    public void setSerial(int serial) {
        this.serial = serial;
    }

    public byte getDev_type() {
        return dev_type;
    }

    public void setDev_type(byte dev_type) {
        this.dev_type = dev_type;
    }

    public byte getCmd() {
        return cmd;
    }

    public void setCmd(byte cmd) {
        this.cmd = cmd;
    }

    public byte[] getCmd_body() {
        return cmd_body;
    }

    public void setCmd_body(byte[] cmd_body) {
        this.cmd_body = cmd_body;
    }


}

class Cmd_body_hub_who implements Serializable {
    private String dev_name;

    public String getDev_name() {
        return dev_name;
    }

    public void setDev_name(String dev_name) {
        this.dev_name = dev_name;
    }


}

class Cmd_body_who_is_where_i_am_here extends Cmd_body_hub_who {

    private byte[] dev_props;

    public byte[] getDev_props() {
        return dev_props;
    }

    public void setDev_props(byte[] dev_props) {
        this.dev_props = dev_props;
    }
}

class Cmd_body_timer implements Serializable {
    private int timestamp;

    public int getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(int timestamp) {
        this.timestamp = timestamp;
    }
}

class Dev_props_sensor_who_i_am implements Serializable {
    private byte sensors;
    private Trigger[] triggers;

    public byte getSensors() {
        return sensors;
    }

    public void setSensors(byte sensors) {
        this.sensors = sensors;
    }

    public Trigger[] getTriggers() {
        return triggers;
    }

    public void setTriggers(Trigger[] triggers) {
        this.triggers = triggers;
    }
}

class Trigger implements Serializable {
    private byte op;
    private int value;
    private String name;

    public byte getOp() {
        return op;
    }

    public void setOp(byte op) {
        this.op = op;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

class Cmd_body_sensor_status implements Serializable {
    private int[] values;

    public int[] getValues() {
        return values;
    }

    public void setValues(int[] values) {
        this.values = values;
    }
}

class Dev_props_switch_who_i_am implements Serializable {
    private String[] device_names;

    public String[] getDevice_names() {
        return device_names;
    }

    public void setDevice_names(String[] device_names) {
        this.device_names = device_names;
    }
}
