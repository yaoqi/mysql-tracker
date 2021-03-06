package protocol.json;

import monitor.JrdwMonitorVo;
import net.sf.json.JSONObject;

import java.util.Map;

/**
 * Created by hp on 15-1-8.
 */
public class JSONConvert {

    public static JSONObject MapToJson(Map m) throws Exception {
        if(m == null) return null;
        return JSONObject.fromObject(m);
    }

    public static JSONObject JrdwMonitorVoToJson(JrdwMonitorVo jmv) throws Exception {
        if(jmv == null) return null;
        return JSONObject.fromObject(jmv);
    }
}
