package com.example;
import com.fazecast.jSerialComm.*;

//TIP コードを<b>実行</b>するには、<shortcut actionId="Run"/> を押すか
// ガターの <icon src="AllIcons.Actions.Execute"/> アイコンをクリックします。
public class Main {
    public static void main(String[] args) {

        SerialPort port = SerialPort.getCommPort("COM1");
        port.setBaudRate(9600);

        if(port.openPort()){
            System.out.print("ポート開きました");
        }else{
            System.out.println("ポートが開けませんでした");
        }

        try{
            byte[] buffer = new byte[1024];
            int numRead = port.getInputStream().read(buffer);
        }catch(Exception e){
            e.printStackTrace();
        }finally {
            port.closePort();
            System.out.println("ポートを閉じました");
        }
    }
}