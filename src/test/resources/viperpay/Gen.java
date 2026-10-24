import java.io.*;
import java.util.*;
import com.verifone.isd.pcapps.simulators.payment.viperpay.comm.pres.DisplayRequest;
import com.verifone.isd.pcapps.simulators.payment.viperpay.comm.pres.ViperpayUserEvent;

public class Gen {
    static void write(Object o, String path) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(path))) { out.writeObject(o); }
    }
    public static void main(String[] args) throws Exception {
        ViperpayUserEvent key = new ViperpayUserEvent();
        key.setPopID("1");
        key.setSoftKey("$0.51");
        write(key, args[0] + "/user_event_key.bin");

        DisplayRequest d = new DisplayRequest();
        d.setPopID("1");
        d.setCommandType("GET_ANY_KEY");
        d.setMessage(new ArrayList<>(Arrays.asList("Would you like to donate", "to CHARITY?")));
        d.setKeyList(new ArrayList<>(Arrays.asList("$0.51", "No")));
        d.setMinDigits(0);
        d.setMaxDigits(0);
        write(d, args[0] + "/display_request_get_any_key.bin");

        // PATS slideCard
        ViperpayUserEvent card = new ViperpayUserEvent();
        card.setCard("EMV AMMEX CREDIT");
        card.setPopID("1");
        write(card, args[0] + "/user_event_card.bin");

        // PATS getAnyDecimals
        ViperpayUserEvent entry = new ViperpayUserEvent();
        entry.setPopID("1");
        entry.setId("123456");
        write(entry, args[0] + "/user_event_decimal.bin");

        DisplayRequest swipe = new DisplayRequest();
        swipe.setPopID("001");
        swipe.setCommandType("READ_CARD");
        swipe.setMessage(new ArrayList<>(Arrays.asList("SWIPE CARD")));
        write(swipe, args[0] + "/display_request_read_card.bin");

        DisplayRequest decimals = new DisplayRequest();
        decimals.setPopID("1");
        decimals.setCommandType("GET_DECIMALS");
        decimals.setMessage(new ArrayList<>(Arrays.asList("Enter Loyalty ID")));
        decimals.setMinDigits(1);
        decimals.setMaxDigits(19);
        write(decimals, args[0] + "/display_request_get_decimals.bin");

        // WAIT_FOR_PROMPT, DISPLAY_KEY_EXACT_MATCH=YES (Swipe Ahead): two lines, which
        // PATS's getPrompt joins with "," ...
        DisplayRequest welcome = new DisplayRequest();
        welcome.setPopID("001");
        welcome.setCommandType("READ_CARD");
        welcome.setMessage(new ArrayList<>(Arrays.asList("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD")));
        write(welcome, args[0] + "/display_request_welcome.bin");

        // ... and one line between empty ones, which it drops before joining.
        DisplayRequest welcomeOnly = new DisplayRequest();
        welcomeOnly.setPopID("001");
        welcomeOnly.setCommandType("READ_CARD");
        welcomeOnly.setMessage(new ArrayList<>(Arrays.asList("", "WELCOME TO OUR STORE", "")));
        write(welcomeOnly, args[0] + "/display_request_welcome_only.bin");
    }
}
