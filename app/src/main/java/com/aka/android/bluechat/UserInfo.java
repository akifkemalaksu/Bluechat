package com.aka.android.bluechat;

import java.io.Serializable;

public class UserInfo implements Serializable {
    String name;
    String macAddress;

    UserInfo(String name, String macAddress) {
        this.name = name;
        this.macAddress = macAddress;
    }

    static UserInfo getUserInfo(String user) {
        String usersSplitUp[] = user.split("\\n");

        String name = usersSplitUp[0];
        String userMacAddress = usersSplitUp[1];

        return new UserInfo(name, userMacAddress);
    }

    public String getName(){
        return name;
    }
    public String getMacAddress(){
        return macAddress;
    }
}
