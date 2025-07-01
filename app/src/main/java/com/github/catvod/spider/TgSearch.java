package com.github.catvod.spider;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author zhixc
 */
public class TgSearch extends Cloud {

    private final String URL = "https://tg.252035.xyz/";

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", Util.CHROME);
        return header;
    }



    @Override
    public String searchContent(String key, boolean quick) throws Exception {

        String url = URL + "?channelUsername=tianyirigeng,tyypzhpd,XiangxiuNB,yunpanpan,kuakeyun,zaihuayun,Quark_Movies,alyp_4K_Movies,vip115hot,yunpanshare,dianyingshare&keyword=" + URLEncoder.encode(key, Charset.defaultCharset().name());
        List<Vod> list = new ArrayList<>();
        String html = OkHttp.string(url, getHeader());
        String[] arr = html.split(":I");
        if (arr.length > 0) {
            for (String s : arr) {
                Document doc = Jsoup.parse(Util.findByRegex("链接(.*)", s, 1));
                String id = doc.select(" a").attr("href");
                String name = Util.findByRegex("名称(.*)描述", s, 1).replace("：","").replace(":","");
                String desc =  Util.findByRegex("描述(.*) 链接", s, 1).replace("：","").replace(":","");

                list.add(new Vod(id, name, "", desc));
            }
        }


        return Result.string(list);
    }
}
