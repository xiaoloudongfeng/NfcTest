package com.example.gg.nfcproject;

import android.nfc.tech.IsoDep;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;

/**
 * Created by xiaolou on 2017/8/27.
 */

public class PBOCUtil {
    private IsoDep mIsoDep;
    private HashMap<String, byte[]> mTlvMap;
    private static final String TAG = "PBOCUtil";

    public PBOCUtil(IsoDep isoDep) {
        mIsoDep = isoDep;
        mTlvMap = new HashMap<>();
    }

    private byte[] stringToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = ((byte) ((Character.digit(s.charAt(i), 16) << 4) +
                    Character.digit(s.charAt(i + 1), 16)));
        }
        return data;
    }

    private String bytesToString(byte[] data) {
        String temp = "";
        for (byte d : data) {
            temp += String.format("%02X", d);
        }
        return temp;
    }

    private int pbocParseTlv(byte[] data) {
        int data_len;
        int off;
        byte[] tag;
        int tag_len;
        byte[] len;
        int len_len;
        byte[] val;
        int val_len;

        // 去掉结尾的90 00
        data_len = data.length - 2;
        if (data_len < 3) {
            Log.e(TAG, "data length too small: " + data_len);
            return -1;
        }

        off = 0;
        while (off < data_len) {
            // 解析出TAG
            if ((data[off] & 0x1F) == 0x1F) {
                tag_len = 2;
            } else {
                tag_len = 1;
            }
            if (off + tag_len >= data_len) {
                Log.e(TAG, String.format(Locale.CHINA, "off[%d], data_len[%d] too small", off, data_len));
                return -1;
            }
            tag = new byte[tag_len];
            System.arraycopy(data, off, tag, 0, tag_len);
            Log.d(TAG, bytesToString(tag));
            off += tag_len;

            // 解析出LENGTH
            if ((data[off] & 0x80) != 0) {
                len_len = (data[off] & 0x7F) + 1;
            } else {
                len_len = 1;
            }
            if (off + len_len >= data_len) {
                if (off + len_len == data_len && data[off] == 0x00) {
                    Log.w(TAG, "get an abnormal tag");
                } else {
                    Log.e(TAG, String.format(Locale.CHINA, "off[%d], data_len[%d] too small", off, data_len));
                    return -1;
                }
            }
            len = new byte[len_len];
            System.arraycopy(data, off, len, 0, len_len);
            Log.d(TAG, bytesToString(len));
            off += len_len;

            // 解析出VALUE
            if (len_len == 1) {
                val_len = (len[0] & 0xFF);
            } else {
                val_len = 0;
                for (int i = 1; i < len_len; i++) {
                    val_len = (val_len << 8) + (len[i] & 0xFF);
                }
            }

            if (off + val_len > data_len) {
                Log.e(TAG, String.format(Locale.CHINA, "off[%d], data_len[%d] too small", off, data_len));
                return -1;
            }
            val = new byte[val_len];
            System.arraycopy(data, off, val, 0, val_len);
            Log.d(TAG, bytesToString(val));

            // 可能value里面还包含标签
            if ((tag[0] & 0x20) != 0x20) {
                off += val_len;
            }

            mTlvMap.put(bytesToString(tag), val);
        }

        return 0;
    }

    private byte[] pbocSendApdu(byte[] apdu) {
        try {
            byte[] data = mIsoDep.transceive(apdu);

            Log.i(TAG, bytesToString(apdu) + "[" + bytesToString(data) + "]");

            if (data.length == 2) {
                switch (data[0]) {
                    // 不加break，下面的代码需要执行
                    case 0x61:
                        apdu = new byte[]{ 0x00, (byte) 0xC0, 0x00, 0x00, 0x00 };
                    case 0x6C:
                        apdu[4] = data[1];
                        data = mIsoDep.transceive(apdu);
                        break;

                    default:
                        return null;
                }
            }

            return data;

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private int pbocCmdSelect(byte[] df) {
        ByteBuffer byteBuffer;

        byteBuffer = ByteBuffer.allocate(6 + df.length);
        byteBuffer.put((byte) 0x00)
                .put((byte) 0xA4)
                .put((byte) 0x04)
                .put((byte) 0x00)
                .put((byte) df.length)
                .put(df)
                .put((byte) 0x00);

        byte[] val = pbocSendApdu(byteBuffer.array());
        if (val == null) {
            return -1;
        }

        return pbocParseTlv(val);
    }

    private byte[] pbocCmdReadRecord(int record_no, byte sfi, boolean parse) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(5);

        byteBuffer.put((byte) 0x00)
                .put((byte) 0xB2)
                .put((byte) record_no)
                .put((byte) (0x04 | sfi))
                .put((byte) 0x00);

        byte[] val = pbocSendApdu(byteBuffer.array());
        if (val == null) {
            return null;
        }

        if (parse) {
            if (pbocParseTlv(val) < 0) {
                return null;
            }
        }

        return val;
    }

    private int pbocCmdGPO(byte[] dol_dest) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(6 + dol_dest.length);

        byteBuffer.put((byte) 0x80)
                .put((byte) 0xA8)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) dol_dest.length)
                .put(dol_dest)
                .put((byte) 0x00);

        byte[] val = pbocSendApdu(byteBuffer.array());
        if (val == null) {
            return -1;
        }

        return pbocParseTlv(val);
    }

    private int pbocCmdGenAC(byte[] dol_dest, byte param) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(6 + dol_dest.length);

        byteBuffer.put((byte) 0x80)
                .put((byte) 0xAE)
                .put(param)
                .put((byte) 0x00)
                .put((byte) dol_dest.length)
                .put(dol_dest)
                .put((byte) 0x00);

        byte[] val = pbocSendApdu(byteBuffer.array());
        if (val == null) {
            return -1;
        }

        return pbocParseTlv(val);
    }

    private byte[] pbocCmdGetData(byte[] tag, boolean parse) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(3 + tag.length);

        byteBuffer.put((byte) 0x80)
                .put((byte) 0xCA)
                .put(tag)
                .put((byte)0x00);

        byte[] val = pbocSendApdu(byteBuffer.array());
        if (val == null) {
            return null;
        }

        if (parse) {
            if (pbocParseTlv(val) < 0) {
                return null;
            }
        }

        return val;
    }

    @Nullable
    private byte[] pbocGenDol(byte[] dol) {
        int dol_off = 0;
        int dol_len = dol.length;
        int dol_dest_off = 0;

        byte[] tag;
        int tag_len;
        byte[] len;
        int len_len;
        byte[] val;
        int val_len;

        ByteBuffer byteBuffer = ByteBuffer.allocate(4096);
        while (dol_off < dol_len) {
            // 解析出TAG
            if ((dol[dol_off] & 0x1F) == 0x1F) {
                tag_len = 2;
            } else {
                tag_len = 1;
            }
            if (dol_off + tag_len >= dol_len) {
                Log.e(TAG, String.format(Locale.CHINA, "off[%d], dol_len[%d] too small", dol_off, dol_len));
                return null;
            }
            tag = new byte[tag_len];
            System.arraycopy(dol, dol_off, tag, 0, tag_len);
            Log.d(TAG, bytesToString(tag));
            dol_off += tag_len;

            // 解析出LENGTH
            if ((dol[dol_off] & 0x80) != 0) {
                len_len = (dol[dol_off] & 0x7F) + 1;
            } else {
                len_len = 1;
            }
            if (dol_off + len_len > dol_len) {
                if (dol_off + len_len == dol_len && dol[dol_off] == 0x00) {
                    Log.d(TAG, "get an abnormal tag");
                } else {
                    Log.e(TAG, String.format(Locale.CHINA, "off[%d], dol_len[%d] too small", dol_off, dol_len));
                    return null;
                }
            }
            len = new byte[len_len];
            System.arraycopy(dol, dol_off, len, 0, len_len);
            Log.d(TAG, bytesToString(len));
            dol_off += len_len;

            // 解析出VALUE
            if (len_len == 1) {
                val_len = (len[0] & 0xFF);
            } else {
                val_len = 0;
                for (int i = 1; i < len_len; i++) {
                    val_len = (val_len << 8) + (len[i] & 0xFF);
                }
            }

            val = mTlvMap.get(bytesToString(tag));
            if (val == null) {
                Log.e(TAG, "get Tag[" + bytesToString(tag) + "] failed");
                return null;
            }

            if (val.length != val_len) {
                Log.e(TAG, String.format(Locale.CHINA, "invalid val, val.length[%08x] val_len[%08x]",
                        val.length, val_len));
                return null;
            }

            byteBuffer.put(val);
            dol_dest_off += val.length;
        }

        byte[] dol_dest = new byte[dol_dest_off];
        byteBuffer.flip();
        byteBuffer.get(dol_dest);

        return dol_dest;
    }

    public void pbocInsertTLV(String tag, String value) {
        if (mTlvMap != null) {
            mTlvMap.put(tag, stringToBytes(value));
        }
    }

    // 应用选择
    public int pbocAppSelect() {
        // 目录选择方式
        if (pbocCmdSelect("1PAY.SYS.DDF01".getBytes()) < 0) {
            Log.e(TAG, "pbocCmdSelect(\"1PAY.SYS.DDF01\") failed");
            return -1;
        }

        /*
        // 遍历Map
        // 当用byte[]作为key的时候，用的是byte[]地址
        // 作为HashCode，而不是内容，所以是有问题的，
        // 解决方案之一是转为String
        Iterator iter = mTlvMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry entry = (Map.Entry) iter.next();
            Object key = entry.getKey();
            Object val = entry.getValue();
            Log.e(TAG, (String) key);
            Log.e(TAG, bytesToString((byte[]) val));
        }
        */
        // 目录基本文件的SFI
        byte[] val = mTlvMap.get("88");
        if (val == null) {
            Log.e(TAG, "get Tag[88] failed");
            return -1;
        }
        if (pbocCmdReadRecord(1, (byte )(val[0] << 3), true) == null) {
            Log.e(TAG, "pbocCmdReadRecord() failed");
            return -1;
        }

        // 选择卡片AID
        val = mTlvMap.get("4F");
        if (val == null) {
            Log.e(TAG, "get Tag[4F] failed");
            return -1;
        }
        if (pbocCmdSelect(val) < 0) {
            Log.e(TAG, "pbocCmdSelect() failed");
            return -1;
        }

        return 0;
    }

    // 应用初始化
    public int pbocAppInit() {
        // 查询PDOL
        byte[] dol = mTlvMap.get("9F38");
        if (dol == null) {
            Log.e(TAG, "get Tag[9F38] failed");
            return -1;
        }

        // 生成PDOL数据
        byte[] dol_dest = pbocGenDol(dol);
        if (dol_dest == null) {
            Log.e(TAG, "pbocGenDol() failed");
            return -1;
        }

        Log.i(TAG, "dol_test.length: " + dol_dest.length);
        ByteBuffer byteBuffer = ByteBuffer.allocate(2 + dol_dest.length);
        byteBuffer.put((byte) 0x83)
                .put((byte) dol_dest.length)
                .put(dol_dest);

        // 发送PDOL数据
        if (pbocCmdGPO(byteBuffer.array()) < 0) {
            Log.e(TAG, "pbocCmdGPO() failed");
            return -1;
        }

        // 获取80标签的值：应用交互特征(AIP)[2byte] + 应用文件定位器(AFL)[每组4byte，可能有多组]
        byte[] val = mTlvMap.get("80");
        if (val == null) {
            Log.e(TAG, "get Tag[80] failed");
            return -1;
        }

        /*
         *  应用文件定位器(AFL)包含终端将要读取用来交易处理的卡片数据文件的 SFI 和记录范围。
         *  每个要读取的文件在 AFL中对应四个字节，含义如下：
         *  字节1：短文件标识符
         *  字节2：文件中要读取的第1个记录的记录号
         *  字节3：文件中要读取的最后一个记录的记录号
         *  字节4：从第1个记录开始的用于脱机数据认证的连续记录数
         */
        int afl_len = val.length - 2;
        byte[] afl = new byte[afl_len];
        System.arraycopy(val, 2, afl, 0, afl_len);
        for (int i = 0; i + 3 < afl_len; i += 4) {
            for (int j = afl[i + 1]; j <= afl[i + 2]; j++) {
                if (pbocCmdReadRecord(j, afl[i], true) == null) {
                    Log.e(TAG, "pbocAppInit->pbocCmdReadRecord failed");
                    return -1;
                }
            }
        }

        return 0;
    }

    // 终端行为分析
    public int pbocTermActAnalyze() {
        // 查询卡片风险管理数据对象列表1(CDOL1)
        byte[] cdol = mTlvMap.get("8C");
        if (cdol == null) {
            Log.e(TAG, "get Tag[8C] failed");
            return -1;
        }

        // 生成应用密文
        byte[] dol_dest = pbocGenDol(cdol);
        if (dol_dest == null) {
            Log.e(TAG, "pbocGenDol() failed");
            return -1;
        }

        if (pbocCmdGenAC(dol_dest, (byte) 0x80) < 0) {
            Log.e(TAG, "pbocCmdGenAC() failed");
            return -1;
        }

        return 0;
    }

    // 获取并解析出详细交易日志
    public String[] pbocGetTransDetail() {
        HashMap<String, String> transMap = new HashMap<>();
        transMap.put("9A", "交易日期");
        transMap.put("9F21", "交易时间");
        transMap.put("9F02", "授权金额");
        transMap.put("9F03", "其它金额");
        transMap.put("9F1A", "终端国家代码");
        transMap.put("5F2A", "交易货币代码");
        transMap.put("9F4E", "商户名称");
        transMap.put("9C", "交易类型");
        transMap.put("9F36", "应用交易计数器(ATC)");

        // 交易日志入口
        byte[] transDetailEntry = mTlvMap.get("9F4D");
        if (transDetailEntry == null) {
            Log.e(TAG, "get Tag[9F4D] failed");
            return null;
        }
        Log.d(TAG, "9F4D: " + bytesToString(transDetailEntry));

        // 交易日志格式
        pbocCmdGetData(stringToBytes("9F4F"), true);
        byte[] transDetailFormat = mTlvMap.get("9F4F");
        if (transDetailFormat == null) {
            Log.e(TAG, "get Tag[9F4F] failed");
            return null;
        }

        // 获取交易日志
        int transDetailNum = transDetailEntry[1];
        byte[][] transDetailArray = new byte[transDetailNum][];
        int idx;
        for (idx = 0; idx < transDetailNum; idx++) {
            transDetailArray[idx] = pbocCmdReadRecord(idx + 1,
                    (byte) (transDetailEntry[0] << 3), false);
            if (transDetailArray[idx] == null) {
                Log.d(TAG, "transDetail ends to " + (idx));
                break;
            }
            Log.d(TAG, "trans: " + bytesToString(transDetailArray[idx]));
        }

        String[] transDetailStringArray = new String[idx];
        for (int j = 0; j < idx; j++) {
            // 根据交易日志格式，解析交易日志
            byte[] dol = transDetailFormat;
            int dol_off = 0;
            int dol_len = dol.length;

            int tag_len;
            byte[] tag;
            int len_len;
            byte[] len;
            int val_len;
            byte val[];

            int val_off = 0;
            String transLog = "";

            while (dol_off < dol_len) {
                // 解析出TAG
                if ((transDetailFormat[dol_off] & 0x1F) == 0x1F) {
                    tag_len = 2;
                } else {
                    tag_len = 1;
                }
                if (dol_off + tag_len >= dol_len) {
                    Log.e(TAG, String.format(Locale.CHINA,
                            "off[%d], dol_len[%d] too small", dol_off, dol_len));
                    return null;
                }
                tag = new byte[tag_len];
                System.arraycopy(transDetailFormat, dol_off, tag, 0, tag_len);
                Log.d(TAG, bytesToString(tag));
                dol_off += tag_len;

                // 解析出LENGTH
                if ((dol[dol_off] & 0x80) != 0) {
                    len_len = (dol[dol_off] & 0x7F) + 1;
                } else {
                    len_len = 1;
                }
                if (dol_off + len_len > dol_len) {
                    if (dol_off + len_len == dol_len && dol[dol_off] == 0x00) {
                        Log.d(TAG, "get an abnormal tag");
                    } else {
                        Log.e(TAG, String.format(Locale.CHINA,
                                "off[%d], dol_len[%d] too small", dol_off, dol_len));
                        return null;
                    }
                }
                len = new byte[len_len];
                System.arraycopy(dol, dol_off, len, 0, len_len);
                Log.d(TAG, bytesToString(len));
                dol_off += len_len;

                // 解析出VALUE
                if (len_len == 1) {
                    val_len = (len[0] & 0xFF);
                } else {
                    val_len = 0;
                    for (int i = 1; i < len_len; i++) {
                        val_len = (val_len << 8) + (len[i] & 0xFF);
                    }
                }

                if (val_off + val_len > transDetailArray[j].length) {
                    Log.e(TAG, String.format(Locale.CHINA,
                            "val_off[%d] + val_len[%d] > transDetailArray[%d].length[%d]",
                            val_off, val_len, j, transDetailArray[j].length));
                    return null;
                }

                val = new byte[val_len];
                System.arraycopy(transDetailArray[j], val_off, val, 0, val_len);
                val_off += val_len;

                if (bytesToString(tag).equals("9F4E")) {
                    int tmpIdx = 0;
                    for ( ; tmpIdx < val_len; tmpIdx++) {
                        if (val[tmpIdx] == 0x00) {
                            break;
                        }
                    }
                    String shop = "";
                    try {
                        shop = new String(val, 0, tmpIdx, "gb2312");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                        shop = "";
                    }
                    transLog += transMap.get(bytesToString(tag)) + "[" + shop + "]|";
                } else {
                    transLog += transMap.get(bytesToString(tag)) + "[" + bytesToString(val) + "]|";
                }
            }

            transDetailStringArray[j] = transLog;
            Log.i(TAG, transLog);
        }

        return transDetailStringArray;
    }
}
