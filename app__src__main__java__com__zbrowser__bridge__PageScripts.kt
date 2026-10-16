package com.zbrowser.bridge

import org.json.JSONObject

/**
 * Small JS snippets owned by the app. Each returns a JSON string.
 * Selectors come from assets/selectors/x.json (one file per site).
 */
object PageScripts {

    private fun q(s: String) = JSONObject.quote(s)

    fun ownHandle(sel: JSONObject): String = """
        (function(){
          var a = document.querySelector(${q(sel.getString("profileLink"))});
          if(!a) return JSON.stringify({handle:""});
          var h = (a.getAttribute('href')||'').replace(/^\/+/, '').split('/')[0];
          return JSON.stringify({handle:h.toLowerCase()});
        })()
    """.trimIndent()

    /** Reads posts that are on screen. own = logged in handle (lowercase, no @). */
    fun visiblePosts(sel: JSONObject, own: String): String = """
        (function(){
          var SEL = ${sel};
          var OWN = ${q(own)};
          var arts = Array.prototype.slice.call(document.querySelectorAll(SEL.article));
          var out = [];
          function handleOf(art){
            var un = art.querySelector(SEL.userName);
            if(!un) return "";
            var links = un.querySelectorAll('a[href^="/"]');
            for (var i=0;i<links.length;i++){
              var h=(links[i].getAttribute('href')||'').replace(/^\/+/,'').split('/')[0];
              if(h && h.indexOf('?')<0) return h.toLowerCase();
            }
            return "";
          }
          function urlOf(art){
            var t = art.querySelector(SEL.timeLink);
            var a = t ? t.closest('a') : null;
            if(!a){ a = art.querySelector('a[href*="/status/"]'); }
            return a ? a.href : "";
          }
          function isAd(art){
            var words = SEL.adWords;
            var spans = art.querySelectorAll('span');
            for (var i=0;i<spans.length;i++){
              if(spans[i].children.length===0){
                var t=(spans[i].textContent||'').trim();
                if(words.indexOf(t)>=0) return true;
              }
            }
            return false;
          }
          function isReply(art){
            var txt = art.innerText || '';
            for (var i=0;i<SEL.replyingToWords.length;i++){
              if(txt.indexOf(SEL.replyingToWords[i])>=0) return true;
            }
            return false;
          }
          var meta = arts.map(function(a){ return {art:a, h:handleOf(a), url:urlOf(a)}; });
          for (var i=0;i<meta.length;i++){
            var m = meta[i];
            if(!m.url) continue;
            var te = m.art.querySelector(SEL.text);
            var next = meta[i+1];
            var followedByOwn = !!(OWN && next && next.h===OWN && next.url!==m.url);
            var r = m.art.getBoundingClientRect();
            if(r.bottom<0 || r.top>window.innerHeight) continue;
            out.push({
              url:m.url, author:m.h, text: te ? te.innerText : '',
              isAd:isAd(m.art), isReply:isReply(m.art),
              isOwn: !!(OWN && m.h===OWN),
              alreadyReplied: followedByOwn
            });
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** In a thread view: which article is the main post, and does an own article exist? */
    fun threadInfo(sel: JSONObject, own: String): String = """
        (function(){
          var SEL = ${sel};
          var OWN = ${q(own)};
          var m = location.pathname.match(/\/status\/(\d+)/);
          var pageId = m ? m[1] : "";
          var arts = document.querySelectorAll(SEL.article);
          var mainFound=false, ownFound=false, ownId="";
          for (var i=0;i<arts.length;i++){
            var t = arts[i].querySelector(SEL.timeLink);
            var a = t ? t.closest('a') : null;
            var href = a ? a.href : "";
            var mm = href.match(/\/status\/(\d+)/);
            if(mm && mm[1]===pageId) mainFound=true;
            var un = arts[i].querySelector(SEL.userName);
            var h = "";
            if(un){ var l = un.querySelector('a[href^="/"]'); if(l) h=(l.getAttribute('href')||'').replace(/^\/+/,'').split('/')[0].toLowerCase(); }
            if(OWN && h===OWN && !(mm && mm[1]===pageId)){ ownFound=true; if(mm) ownId=mm[1]; }
          }
          return JSON.stringify({pageId:pageId, mainFound:mainFound, ownReplyVisible:ownFound, ownId:ownId});
        })()
    """.trimIndent()

    /** following | not_following | none, only if the page is the expected profile. */
    fun profileState(sel: JSONObject, expected: String): String = """
        (function(){
          var SEL = ${sel};
          var want = ${q(expected)};
          var p = (location.pathname||'').split('/')[1]||'';
          if(p.toLowerCase()!==want.toLowerCase()) return JSON.stringify({s:'none'});
          var u = document.querySelector(SEL.unfollowButton);
          var f = document.querySelector(SEL.followButton);
          return JSON.stringify({s: u ? 'following' : (f ? 'not_following' : 'none')});
        })()
    """.trimIndent()

    /** X post links currently rendered in the Telegram message list. */
    fun telegramLinks(sel: JSONObject): String = """
        (function(){
          var SEL = ${sel};
          var out = [];
          document.querySelectorAll(SEL.link).forEach(function(a){ if(a.href) out.push(a.href); });
          return JSON.stringify(out);
        })()
    """.trimIndent()

    fun scrollContainerUp(selector: String, px: Int): String = """
        (function(){
          var e = document.querySelector(${q(selector)});
          if(!e) return 'none';
          e.scrollTop = Math.max(0, e.scrollTop - $px);
          return 'ok';
        })()
    """.trimIndent()

    fun pageState(sel: JSONObject): String = """
        (function(){
          var SEL = ${sel};
          var body = (document.body && document.body.innerText) || '';
          var login = false;
          for (var i=0;i<SEL.loginWallSelectors.length;i++){
            if(document.querySelector(SEL.loginWallSelectors[i])) login = true;
          }
          if(/\/i\/flow\/login|\/login/.test(location.pathname)) login = true;
          var err = "";
          for (var j=0;j<SEL.errorWords.length;j++){
            if(body.indexOf(SEL.errorWords[j])>=0) { err = SEL.errorWords[j]; break; }
          }
          return JSON.stringify({login:login, error:err, url:location.href, articles:document.querySelectorAll(SEL.article).length});
        })()
    """.trimIndent()

    /** Center of an element in CSS px plus viewport width, so Kotlin can map to view pixels. */
    fun rectOf(selector: String): String = """
        (function(){
          var e = document.querySelector(${q(selector)});
          if(!e) return JSON.stringify({found:false});
          e.scrollIntoView({block:'center'});
          var r = e.getBoundingClientRect();
          var vv = window.visualViewport;
          return JSON.stringify({found:true, cx:r.left+r.width/2-(vv?vv.offsetLeft:0), cy:r.top+r.height/2-(vv?vv.offsetTop:0), iw:(vv?vv.width:window.innerWidth)});
        })()
    """.trimIndent()

    fun scrollBy(px: Int): String = "(function(){window.scrollBy({top:$px,behavior:'smooth'});return 'ok';})()"

    fun insertText(selector: String, text: String): String = """
        (function(){
          var e = document.querySelector(${q(selector)});
          if(!e) return JSON.stringify({ok:false});
          e.focus();
          var ok = document.execCommand('insertText', false, ${q(text)});
          return JSON.stringify({ok:ok, len:(e.innerText||'').length});
        })()
    """.trimIndent()

    fun boxText(selector: String): String = """
        (function(){ var e=document.querySelector(${q(selector)}); return JSON.stringify({t: e ? (e.innerText||'') : ''}); })()
    """.trimIndent()
}
