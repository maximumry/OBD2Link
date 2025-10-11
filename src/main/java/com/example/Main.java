package com.example;
import com.fazecast.jSerialComm.*;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
//TIP コードを<b>実行</b>するには、<shortcut actionId="Run"/> を押すか
// ガターの <icon src="AllIcons.Actions.Execute"/> アイコンをクリックします。
public class Main {
    //車の情報を格納する配列を宣言
    byte[] portBuffer;
    //OBD2コマンドを定義
    static final String[] ELM327COMMAND = {"ATZ", "ATE0", "ATSP0"};

    public static void main(String[] args) throws InterruptedException {

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

        //try-with-resourcesで事前に書き込み用ファイルを指定
        try (FileWriter file = new FileWriter(
                "/Users/kawaguchiryou/Projects/files/carSpeed.csv"
        )) {
            System.out.println("ポートを開きました。ELM327を初期化します");

            //ELM327を初期化
            Main main = new Main();
            boolean resetResult = main.sendCommand(port, ELM327COMMAND[0]);
            if (!resetResult) return;

            //エコー無効にして送信したコマンドのおうむ返しを防ぐ
            boolean echoResult = main.sendCommand(port, ELM327COMMAND[1]);
            if (!echoResult) return;

            //ECUとのプロトコル自動設定
            boolean protocolResult = main.sendCommand(port, ELM327COMMAND[2]);
            if (!protocolResult) return;

            //車両情報を書き込むファイルを抽出してオブジェクト生成
            PrintWriter pw = new PrintWriter(file, true);

            ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);
            ses.schedule(() -> System.out.println("１回目実行"), 1, TimeUnit.SECONDS);

            ScheduledFuture<?> f1 = ses.scheduleWithFixedDelay(() -> {
                try{
                    byte[] carSpeedResult = main.sendCarCommand(port, "010D");
                    if(carSpeedResult == null || carSpeedResult.length == 0){
                        return;
                    }

                    String speedStr = new String(carSpeedResult, StandardCharsets.UTF_8)
                            .replaceAll("[\\r\\n>]", " ")
                            .trim();

                    //charsetを指定した文字列を空白で区切り、OBD2コマンドごとに抽出
                    String[] carData = speedStr.split("\\s+");
                    for (int i = 0; i + 2 < carData.length; i++){
                        if("41".equals(carData[i]) && "0D".equals(carData[i + 1])){
                            int speedKmh = Integer.parseInt(carData[i + 2], 16);
                            pw.println(speedKmh);
                            break;
                        }
                    }
                }catch(Exception exception){
                    main.writeErrorMessage(exception);
                }
            }, 1, 2, TimeUnit.SECONDS);

            while(port.isOpen()){
                Thread.sleep(500);
            }
            ses.shutdown();
        } catch (NullPointerException e) {
            //要修正
            e.printStackTrace();
        } catch(Exception e){
            //要修正
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

                String responseBuffer = new String(readBuffer, StandardCharsets.UTF_8);
                readString.append(responseBuffer);

                //500msかかって返答なかった場合、強制終了
                if(executionCount >= 500){
                    cancel();
                }
                executionCount++;
            };
        };
        timer.scheduleAtFixedRate(task, 10, 1);

        try{
            Thread.sleep(500);
        }catch (InterruptedException e){
            timer.cancel();
            return false;
        }
        timer.cancel();

        if(readString.length() == 0){
            return false;
        }

        String lastStr = readString.toString().trim();
        if(lastStr.contains(">")){
            return true;
        }
        return lastStr.startsWith("OK");
    };

    private byte[] sendCarCommand(SerialPort port, String command){
        String fullCommand =  command + "\r";
        port.writeBytes(fullCommand.getBytes(StandardCharsets.UTF_8), fullCommand.length());

        long startTime = System.currentTimeMillis();
        while(port.bytesAvailable() == 0){

            // 1秒以上経過したらnullを返却して終了させる
            if(System.currentTimeMillis() - startTime > 1000){
                System.out.println("タイムアウトしました");
                return null;
            }
            try{
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        portBuffer = new byte[port.bytesAvailable()];
        port.readBytes(portBuffer, portBuffer.length);
        return portBuffer;
    };

    private void writeErrorMessage(Exception e){
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS");
        String nl = System.lineSeparator();
        String ts = java.time.LocalDateTime.now().format(dtf);
        StringBuilder errorMessage = new StringBuilder();

        errorMessage.append(nl)
            .append("--------------").append(nl)
            .append("TIMESTAMP: ").append(ts).append(nl)
            .append("LEVEL: ERROR").append(nl)
            .append("MESSAGE: ").append(e.getMessage()).append(nl);
        for(StackTraceElement element : e.getStackTrace()) {
            errorMessage.append("at ")
                    .append(element.getClassName()).append(".").append(element.getMethodName())
                    .append("(").append(element.getFileName()).append(":").append(element.getLineNumber()).append(")")
                    .append(nl);
        }
        System.err.println(errorMessage.toString());
    };
};