package com.example;
import com.fazecast.jSerialComm.*;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

//TIP コードを<b>実行</b>するには、<shortcut actionId="Run"/> を押すか
// ガターの <icon src="AllIcons.Actions.Execute"/> アイコンをクリックします。
public class Main {
    //車の情報を格納する配列を宣言
    byte[] portBuffer;
    //OBD2コマンドを定義
    static final String[] ELM327COMMAND = {"ATZ", "ATE0", "ATSP0"};

    public static void main(String[] args) {

        SerialPort[] ports = SerialPort.getCommPorts();

        if (ports.length == 0) {
            System.out.println("参照可能なポートがありません");
            return;
        }
        SerialPort port = ports[0];
        port.setBaudRate(9600);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);

        if (port.openPort()) {
            System.out.print("ポート開きました");
        } else {
            System.out.println("ポートが開けませんでした");
            return;
        }

        try {
            System.out.println("ポートを開きました。ELM327を初期化します");

            //ELM327を初期化
            Main main = new Main();
            boolean resetResult = main.sendCommand(port, ELM327COMMAND[0]);
            if(!resetResult) return;

            //エコー無効にして送信したコマンドのおうむ返しを防ぐ
            boolean echoResult = main.sendCommand(port, ELM327COMMAND[1]);
            if(!echoResult) return;

            //ECUとのプロトコル自動設定
            boolean protocolResult = main.sendCommand(port, ELM327COMMAND[2]);
            if(!protocolResult) return;

            //車両情報を書き込むファイルを抽出してオブジェクト生成
            FileWriter file = new FileWriter(
                    "/Users/kawaguchiryou/Projects/files/carSpeed.csv"
            );
            PrintWriter pw = new PrintWriter(file);

            Timer time = new Timer();
            TimerTask task = new TimerTask() {
                int sendCount = 0;
                @Override
                public void run() {
                    //モード01でPIDの0D(車速)を取得
                    byte[] carSpeedResult = main.sendCarCommand(port, "010D");

                    String byteValue = "";
                    int speedValue = 0;
                    try{
                        if(carSpeedResult != null && carSpeedResult.length != 0){
                            return;
                        }

                        int obd2Mode = Integer.parseInt(String.valueOf(carSpeedResult[0]), 10);
                        int carSituation = Integer.parseInt(String.valueOf(carSpeedResult[1]), 10);
                        int carSituationResult = Integer.parseInt(String.valueOf(carSpeedResult[2]), 10);

                        //OBD2モードと送ったPIDが合っているか確認
                        if(obd2Mode == 41 && carSituation == 13){
                            //配列最後のデータ(車の情報)をcsvファイルに出力
                            byteValue = String.valueOf(carSituationResult);
                            speedValue = Integer.parseInt(byteValue, 10);
                            pw.println(speedValue);
                        }
                    }catch(NullPointerException e){
                        main.writeErrorMessage(e);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
            };
            time.scheduleAtFixedRate(task, 10, 100);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            port.closePort();
            System.out.println("ポートを閉じました");
        }
    }

    private boolean sendCommand(SerialPort port, String command){
        String fullCommand = command + "\r";
        port.writeBytes(fullCommand.getBytes(StandardCharsets.UTF_8), fullCommand.length());

        Timer timer = new Timer();
        StringBuilder readString = new StringBuilder();
        TimerTask task = new TimerTask(){
            private int executionCount = 0;

            @Override
            public void run(){
                //初期化後">" or "OK"を受け取るための処理
                if(port.bytesAvailable() == -1){
                    return;
                }
                byte[] readBuffer = new byte[port.bytesAvailable()];
                int readResult = port.readBytes(readBuffer, readBuffer.length);
                if(readResult == -1) return;

                String responseBuffer = new String(readBuffer);
                readString.append(responseBuffer);

                //500msかかって返答なかった場合、強制終了
                if(executionCount >= 500){
                    cancel();
                }
                executionCount++;
            };
        };
        timer.scheduleAtFixedRate(task, 10, 1);

        //初期化コマンド送信しても返答がなかった場合の処理
        if(readString.isEmpty()){
            return false;
        }

        String lastStr = readString.toString().substring(readString.length() - 1);
        if(lastStr.equals(">")){
            return true;
        }

        String okResponseCheck = readString.toString().substring(0, 2);
        return okResponseCheck.equals("OK");
    };

    private byte[] sendCarCommand(SerialPort port, String command){
        String fullCommand =  command + "\r";
        port.writeBytes(fullCommand.getBytes(StandardCharsets.UTF_8), fullCommand.length());

        ArrayList<Byte> carResult = new ArrayList<Byte>();
        Timer time = new Timer();
        TimerTask task = new TimerTask() {
            private int executionCount = 0;

            @Override
            public void run() {
                if(port.bytesAvailable() == -1){
                    return;
                }

                portBuffer = new byte[port.bytesAvailable()];
                int readResult = port.readBytes(portBuffer, carResult.size());
                if(readResult == -1){
                    return;
                }

                //100msかかって返答なかった場合、強制終了
                if(executionCount >= 100){
                    cancel();
                }
                executionCount++;
            }
        };
        time.scheduleAtFixedRate(task, 10, 1);

        String lastStr = portBuffer.toString().substring(portBuffer.length - 1);
        if(lastStr.equals(">")) {
            return portBuffer;
        }
        return portBuffer;
    };

    private String writeErrorMessage(Exception e){
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS");
        StringBuilder errorMessage = new StringBuilder();

        errorMessage.append("\r");
        errorMessage.append("--------------" + "\r");
        errorMessage.append("TIMESTAMP: " + dtf + "\r");
        errorMessage.append("LEVEL: ERROR" + "\r");
        errorMessage.append("MESSAGE" + e.getMessage() + "\r");
        for(StackTraceElement element : e.getStackTrace()) {
            errorMessage.append("at" + element.getClassName() + "." + element.getMethodName()
                + "(" + element.getFileName() + ":" + element.getLineNumber() + ")" + "\r");
        }
        errorMessage.append("\r");

        return errorMessage.toString();
    };
};