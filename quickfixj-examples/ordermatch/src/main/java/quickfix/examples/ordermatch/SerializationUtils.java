package quickfix.examples.ordermatch;

import java.io.*;

public class SerializationUtils {

    // Serialize an object to a byte array
    public static byte[] serialize(Object obj) throws IOException {
        try (ByteArrayOutputStream b = new ByteArrayOutputStream();
             ObjectOutputStream o = new ObjectOutputStream(b)) {
            o.writeObject(obj);
            return b.toByteArray();
        }
    }

    // Deserialize an object from a byte array
    public static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream b = new ByteArrayInputStream(bytes);
             ObjectInputStream o = new ObjectInputStream(b)) {
            return o.readObject();
        }
    }

    // Serialize an object to a file
    public static void serializeToFile(Object obj, String filePath) throws IOException {
        try (FileOutputStream f = new FileOutputStream(filePath);
             ObjectOutputStream o = new ObjectOutputStream(f)) {
            o.writeObject(obj);
        }
    }

    // Deserialize an object from a file
    public static Object deserializeFromFile(String filePath) throws IOException, ClassNotFoundException {
        try (FileInputStream f = new FileInputStream(filePath);
             ObjectInputStream o = new ObjectInputStream(f)) {
            return o.readObject();
        }
    }
}