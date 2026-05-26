package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.bili.Dash;
import com.github.catvod.bean.bili.Data;
import com.github.catvod.bean.bili.Media;
import com.github.catvod.bean.bili.Page;
import com.github.catvod.bean.bili.Resp;
import com.github.catvod.bean.bili.Wbi;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author ColaMint & FongMi & 唐三 & 林婉儿优化版
 */
public class Bili extends Spider {

    private static final String COOKIE = "buvid3=84B0395D-C9F2-C490-E92E-A09AB48FE26E71636infoc";
    private static String cookie;

    private JsonObject extend;
    private boolean login;
    private boolean isVip;
    private Wbi wbi;
    private long lastWbiTime = 0;

    private static Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", "https://www.bilibili.com");
        if (cookie != null) headers.put("cookie", cookie);
        return headers;
    }

    private void setCookie() {
        cookie = extend.get("cookie").getAsString();
        if (cookie.startsWith("http")) cookie = OkHttp.string(cookie).trim();
        if (TextUtils.isEmpty(cookie)) cookie = Path.read(getCache());
        if (TextUtils.isEmpty(cookie)) cookie = COOKIE;
    }

    private List<Filter> getFilter() {
        List<Filter> items = new ArrayList<>();
        items.add(new Filter("order", "排序", Arrays.asList(new Filter.Value("預設", "totalrank"), new Filter.Value("最多點擊", "click"), new Filter.Value("最新發布", "pubdate"), new Filter.Value("最多彈幕", "dm"), new Filter.Value("最多收藏", "stow"))));
        items.add(new Filter("duration", "時長", Arrays.asList(new Filter.Value("全部時長", "0"), new Filter.Value("60分鐘以上", "4"), new Filter.Value("30~60分鐘", "3"), new Filter.Value("10~30分鐘", "2"), new Filter.Value("10分鐘以下", "1"))));
        return items;
    }

    private File getCache() {
        return Path.tv("bilibili");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        this.extend = Json.safeObject(extend);
        setCookie();
        checkLogin();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (extend.has("json")) return OkHttp.string(extend.get("json").getAsString());
        List<Class> classes = new ArrayList<>();
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        String[] types = extend.get("type").getAsString().split("#");
        for (String type : types) {
            classes.add(new Class(type));
            filters.put(type, getFilter());
        }
        return Result.string(classes, filters);
    }

    @Override
    public String homeVideoContent() {
        try {
            String api = "https://api.bilibili.com/x/web-interface/popular?ps=20";
            String json = OkHttp.string(api, getHeader());
            Resp resp = Resp.objectFrom(json);
            List<Vod> list = new ArrayList<>();
            for (Resp.Result item : Resp.Result.arrayFrom(resp.getData().getList())) list.add(item.getVod());
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        checkWbiExpired();

        if (tid.endsWith("/{pg}")) {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("mid", tid.split("/")[0]);
            params.put("pn", pg);
            List<Vod> list = new ArrayList<>();
            String json = OkHttp.string("https://api.bilibili.com/x/space/wbi/arc/search?" + wbi.getQuery(params), getHeader());
            for (Resp.Result item : Resp.Result.arrayFrom(Resp.objectFrom(json).getData().getList().getAsJsonObject().get("vlist"))) list.add(item.getVod());
            return Result.string(list);
        } else {
            String order = extend.containsKey("order") ? extend.get("order") : "totalrank";
            String duration = extend.containsKey("duration") ? extend.get("duration") : "0";
            if (extend.containsKey("tid")) tid = tid + " " + extend.get("tid");
            
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("search_type", "video");
            params.put("keyword", tid);
            params.put("order", order);
            params.put("duration", duration);
            params.put("page", pg);

            try {
                String api = "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=" + URLEncoder.encode(tid, "UTF-8") + "&page=" + pg;
                String json = OkHttp.string(api, getHeader());
                JsonObject data = Json.parse(json).getAsJsonObject().getAsJsonObject("data");
                List<Vod> list = new ArrayList<>();
                if (data != null && data.has("result")) {
                    JsonArray resultArr = data.getAsJsonArray("result");
                    for (int i = 0; i < resultArr.size(); i++) {
                        JsonObject r = resultArr.get(i).getAsJsonObject();
                        if (r.has("result_type") && "video".equals(r.get("result_type").getAsString())) {
                            JsonArray items = r.getAsJsonArray("data");
                            for (int j = 0; j < items.size(); j++) {
                                JsonObject item = items.get(j).getAsJsonObject();
                                String bvid = item.has("bvid") ? item.get("bvid").getAsString() : "";
                                String aid = item.has("aid") ? item.get("aid").getAsString() : "";
                                if (TextUtils.isEmpty(bvid)) continue;
                                Vod vod = new Vod();
                                vod.setVodId(bvid + "@" + aid);
                                vod.setVodName(org.jsoup.Jsoup.parse(item.get("title").getAsString()).text());
                                String pic = item.get("pic").getAsString();
                                vod.setVodPic(pic.startsWith("//") ? "https:" + pic : pic);
                                String durationStr = item.has("duration") ? item.get("duration").getAsString() : "";
                                if (durationStr.contains(":")) {
                                    vod.setVodRemarks(durationStr.split(":")[0] + "分鐘");
                                } else {
                                    vod.setVodRemarks(durationStr);
                                }
                                list.add(vod);
                            }
                            break;
                        }
                    }
                }
                return Result.string(list);
            } catch (Exception e) {
                return Result.string(new ArrayList<>());
            }
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!login) checkLogin();

        String[] split = ids.get(0).split("@");
        String bvid = split[0];
        String aid = split[1];

        String api = "https://api.bilibili.com/x/web-interface/view?aid=" + aid;
        String json = OkHttp.string(api, getHeader());
        Data detail = Resp.objectFrom(json).getData();
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodPic(detail.getPic());
        vod.setVodName(detail.getTitle());
        vod.setTypeName(detail.getType());
        vod.setVodContent(detail.getDesc());
        vod.setVodDirector(detail.getOwner().getFormat());
        vod.setVodRemarks(detail.getDuration() / 60 + "分鐘");

        List<String> acceptDesc = new ArrayList<>();
        List<Integer> acceptQuality = new ArrayList<>();
        api = "https://api.bilibili.com/x/player/playurl?avid=" + aid + "&cid=" + detail.getCid() + "&qn=127&fnval=4048&fourk=1";
        json = OkHttp.string(api, getHeader());
        Data play = Resp.objectFrom(json).getData();
        for (int i = 0; i < play.getAcceptQuality().size(); i++) {
            int qn = play.getAcceptQuality().get(i);
            if (!login && qn > 32) continue;
            if (!isVip && qn > 80) continue;
            acceptQuality.add(play.getAcceptQuality().get(i));
            acceptDesc.add(play.getAcceptDescription().get(i));
        }

        List<String> episode = new ArrayList<>();
        LinkedHashMap<String, String> flag = new LinkedHashMap<>();
        for (Page page : detail.getPages()) episode.add(page.getPart() + "$" + aid + "+" + page.getCid() + "+" + TextUtils.join(":", acceptQuality) + "+" + TextUtils.join(":", acceptDesc));
        flag.put("B站", TextUtils.join("#", episode));

        episode = new ArrayList<>();
        api = "https://api.bilibili.com/x/web-interface/archive/related?bvid=" + bvid;
        json = OkHttp.string(api, getHeader());
        JsonArray array = Json.parse(json).getAsJsonObject().getAsJsonArray("data");
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.get(i).getAsJsonObject();
            episode.add(object.get("title").getAsString() + "$" + object.get("aid").getAsInt() + "+" + object.get("cid").getAsInt() + "+" + TextUtils.join(":", acceptQuality) + "+" + TextUtils.join(":", acceptDesc));
        }
        flag.put("相关", TextUtils.join("#", episode));

        vod.setVodPlayFrom(TextUtils.join("$$$", flag.keySet()));
        vod.setVodPlayUrl(TextUtils.join("$$$", flag.values()));
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return categoryContent(key, "1", true, new HashMap<>());
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return categoryContent(key, pg, true, new HashMap<>());
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] ids = id.split("\+");
        String aid = ids[0];
        String cid = ids[1];
        String[] acceptDesc = ids[3].split(":");
        String[] acceptQuality = ids[2].split(":");
        List<String> url = new ArrayList<>();
        String dan = "https://api.bilibili.com/x/v1/dm/list.so?oid=".concat(cid);
        for (int i = 0; i < acceptDesc.length; i++) {
            url.add(acceptDesc[i]);
            url.add(Proxy.getUrl() + "?do=bili" + "&aid=" + aid + "&cid=" + cid + "&qn=" + acceptQuality[i] + "&type=mpd");
        }
        return Result.get().url(url).danmaku(dan).dash().header(getHeader()).string();
    }

    public static Object[] proxy(Map<String, String> params) {
        String aid = params.get("aid");
        String cid = params.get("cid");
        String qn = params.get("qn");
        String api = "https://api.bilibili.com/x/player/playurl?avid=" + aid + "&cid=" + cid + "&qn=" + qn + "&fnval=4048&fourk=1";
        String json = OkHttp.string(api, getHeader());
        Resp resp = Resp.objectFrom(json);
        Dash dash = resp.getData().getDash();
        StringBuilder video = new StringBuilder();
        StringBuilder audio = new StringBuilder();
        findAudio(dash, audio);
        findVideo(dash, video, qn);
        String mpd = getMpd(dash, video.toString(), audio.toString());
        Object[] result = new Object[3];
        result[0] = 200;
        result[1] = "application/dash+xml";
        result[2] = new ByteArrayInputStream(mpd.getBytes());
        return result;
    }

    private static HashMap<String, String> getAudioFormat() {
        HashMap<String, String> audios = new HashMap<>();
        audios.put("30280", "192000");
        audios.put("30232", "132000");
        audios.put("30216", "64000");
        return audios;
    }

    private static void findAudio(Dash dash, StringBuilder sb) {
        for (Media audio : dash.getAudio()) {
            for (String key : getAudioFormat().keySet()) {
                if (audio.getId().equals(key)) {
                    sb.append(getMedia(audio));
                }
            }
        }
    }

    private static void findVideo(Dash dash, StringBuilder sb, String qn) {
        for (Media video : dash.getVideo()) {
            if (video.getId().equals(qn)) {
                sb.append(getMedia(video));
            }
        }
    }

    private static String getMedia(Media media) {
        if (media.getMimeType().startsWith("video")) {
            return getAdaptationSet(media, String.format(Locale.getDefault(), "height='%s' width='%s' frameRate='%s' sar='%s'", media.getHeight(), media.getWidth(), media.getFrameRate(), media.getSar()));
        } else if (media.getMimeType().startsWith("audio")) {
            return getAdaptationSet(media, String.format("numChannels='2' sampleRate='%s'", getAudioFormat().get(media.getId())));
        } else {
            return "";
        }
    }

    private static String getAdaptationSet(Media media, String params) {
        String id = media.getId() + "_" + media.getCodecId();
        String type = media.getMimeType().split("/")[0];
        String baseUrl = media.getBaseUrl().replace("&", "&amp;");
        return String.format(Locale.getDefault(), "<AdaptationSet>\n" + "<ContentComponent contentType=\"%s\"/>\n" + "<Representation id=\"%s\" bandwidth=\"%s\" codecs=\"%s\" mimeType=\"%s\" %s startWithSAP=\"%s\">\n" + "<BaseURL>%s</BaseURL>\n" + "<SegmentBase indexRange=\"%s\">\n" + "<Initialization range=\"%s\"/>\n" + "</SegmentBase>\n" + "</Representation>\n" + "</AdaptationSet>", type, id, media.getBandWidth(), media.getCodecs(), media.getMimeType(), params, media.getStartWithSap(), baseUrl, media.getSegmentBase().getIndexRange(), media.getSegmentBase().getInitialization());
    }

    private static String getMpd(Dash dash, String videoList, String audioList) {
        return String.format(Locale.getDefault(), "<MPD xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xsi:schemaLocation=\"urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd\" type=\"static\" mediaPresentationDuration=\"PT%sS\" minBufferTime=\"PT%sS\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n" + "<Period duration=\"PT%sS\" start=\"PT0S\">\n" + "%s\n" + "%s\n" + "</Period>\n" + "</MPD>", dash.getDuration(), dash.getMinBufferTime(), dash.getDuration(), videoList, audioList);
    }

    private synchronized void checkLogin() {
        try {
            String json = OkHttp.string("https://api.bilibili.com/x/web-interface/nav", getHeader());
            Data data = Resp.objectFrom(json).getData();
            login = data.isLogin();
            isVip = data.isVip();
            wbi = data.getWbi();
            lastWbiTime = System.currentTimeMillis();
        } catch (Exception e) {
            if (wbi == null) {
                wbi = new Wbi();
            }
        }
    }

    private void checkWbiExpired() {
        if (wbi == null || (System.currentTimeMillis() - lastWbiTime > 3600 * 1000)) {
            checkLogin();
        }
    }
}
