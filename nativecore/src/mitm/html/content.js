(function (endpoint, generic, filters) {
  'use strict';
  // Injected by BlockAds next to its <style>. Never sends page data off the device: the
  // endpoint is answered by the local HTTPS proxy and is never forwarded to the origin.
  var doc = document;
  var self = doc.currentScript;
  var nonce = self ? self.nonce : '';
  // Generic `.class`/`#id` rules are looked up only for tokens that actually appear.
  var seen = new Set();
  var classes = [];
  var ids = [];
  var flushTimer = 0;
  function note(el) {
    var id = el.id;
    if (typeof id === 'string' && id && id.length <= 256 && !seen.has('#' + id)) {
      seen.add('#' + id);
      ids.push(id);
    }
    var list = el.classList;
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (c.length <= 256 && !seen.has('.' + c)) {
        seen.add('.' + c);
        classes.push(c);
      }
    }
  }
  function scan(root) {
    if (!generic || root.nodeType !== 1) return;
    note(root);
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) note(all[i]);
  }
  function take(from, into, size) {
    while (from.length && size < 6000) {
      var token = from.pop();
      size += encodeURIComponent(token).length + 3;
      into.push(token);
    }
    return size;
  }
  function flush() {
    flushTimer = 0;
    while (classes.length || ids.length) {
      // Stay well below the proxy's 16 KiB request header limit.
      var c = [];
      var i = [];
      take(ids, i, take(classes, c, 0));
      var link = doc.createElement('link');
      link.rel = 'stylesheet';
      link.nonce = nonce;
      link.setAttribute('data-blockads', '');
      link.href = endpoint + '?c=' + encodeURIComponent(c.join(' ')) + '&i=' + encodeURIComponent(i.join(' '));
      (doc.head || doc.documentElement).appendChild(link);
    }
  }
  function queueFlush(delay) {
    if (generic && !flushTimer && (classes.length || ids.length)) flushTimer = setTimeout(flush, delay);
  }

  // Procedural and action filters from adblock-rust (`:has-text`, `:upward`, `:remove()`...).
  function regex(arg) {
    var m = /^\/(.+)\/([dgimsuy]*)$/.exec(arg);
    if (!m) return null;
    try {
      return new RegExp(m[1], m[2]);
    } catch (e) {
      return /(?!)/;
    }
  }
  function contains(arg) {
    var re = regex(arg);
    return re ? function (s) { return re.test(s); } : function (s) { return s.indexOf(arg) !== -1; };
  }
  function exact(arg) {
    var re = regex(arg);
    return re ? function (s) { return re.test(s); } : function (s) { return s === arg; };
  }
  function unquote(s) {
    s = s.trim();
    return /^(["']).*\1$/.test(s) ? s.slice(1, -1) : s;
  }
  function unique(nodes) {
    return Array.from(new Set(nodes));
  }
  var scopeId = 0;
  function step(nodes, op, first) {
    var type = op.type;
    var arg = op.arg;
    var out = [];
    if (type === 'css-selector') {
      if (first) return Array.from(doc.querySelectorAll(arg));
      var sibling = /^\s*[+~]/.test(arg);
      nodes.forEach(function (el) {
        var found;
        if (sibling) {
          var mark = 'b' + ++scopeId;
          el.setAttribute('data-blockads-scope', mark);
          found = doc.querySelectorAll('[data-blockads-scope="' + mark + '"]' + arg);
          el.removeAttribute('data-blockads-scope');
        } else {
          found = el.querySelectorAll(':scope ' + arg);
        }
        for (var i = 0; i < found.length; i++) out.push(found[i]);
      });
      return unique(out);
    }
    if (first) nodes = type === 'xpath' ? [doc] : Array.from(doc.querySelectorAll('body *'));
    switch (type) {
      case 'has-text':
        var text = contains(arg);
        return nodes.filter(function (el) { return text(el.textContent || ''); });
      case 'min-text-length':
        var min = parseInt(arg, 10) || 0;
        return nodes.filter(function (el) { return (el.textContent || '').length >= min; });
      case 'matches-path':
        return contains(arg)(location.pathname + location.search) ? nodes : [];
      case 'matches-attr':
        var at = arg.indexOf('=');
        var name = exact(unquote(at < 0 ? arg : arg.slice(0, at)));
        var value = at < 0 ? function () { return true; } : exact(unquote(arg.slice(at + 1)));
        return nodes.filter(function (el) {
          for (var i = 0; i < el.attributes.length; i++) {
            if (name(el.attributes[i].name) && value(el.attributes[i].value)) return true;
          }
          return false;
        });
      case 'matches-css':
      case 'matches-css-before':
      case 'matches-css-after':
        var colon = arg.indexOf(':');
        if (colon < 0) return [];
        var prop = arg.slice(0, colon).trim();
        var want = exact(arg.slice(colon + 1).trim());
        var pseudo = type === 'matches-css' ? null : '::' + type.slice(12);
        return nodes.filter(function (el) {
          return want(getComputedStyle(el, pseudo).getPropertyValue(prop).trim());
        });
      case 'upward':
        var n = Number(arg);
        return unique(nodes.map(function (el) {
          if (n >= 1 && n <= 256 && n === Math.floor(n)) {
            for (var k = 0; el && k < n; k++) el = el.parentElement;
            return el;
          }
          return el.parentElement && el.parentElement.closest(arg);
        }).filter(Boolean));
      case 'xpath':
        nodes.forEach(function (context) {
          var result = doc.evaluate(arg, context, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
          for (var i = 0; i < result.snapshotLength; i++) {
            if (result.snapshotItem(i).nodeType === 1) out.push(result.snapshotItem(i));
          }
        });
        return unique(out);
    }
    return [];
  }
  function act(el, action) {
    var style = el.style;
    if (!action) {
      if (style.getPropertyValue('display') !== 'none' || style.getPropertyPriority('display') !== 'important') {
        style.setProperty('display', 'none', 'important');
      }
      return;
    }
    switch (action.type) {
      case 'remove':
        el.remove();
        break;
      case 'style':
        var parsed = doc.createElement('div').style;
        parsed.cssText = action.arg;
        for (var i = 0; i < parsed.length; i++) {
          style.setProperty(parsed[i], parsed.getPropertyValue(parsed[i]), 'important');
        }
        break;
      case 'remove-attr':
        action.arg.split('|').forEach(function (a) {
          if (el.hasAttribute(a.trim())) el.removeAttribute(a.trim());
        });
        break;
      case 'remove-class':
        // classList.remove() always writes the attribute; check first so it does not re-trigger.
        action.arg.split('|').forEach(function (c) {
          if (el.classList.contains(c.trim())) el.classList.remove(c.trim());
        });
        break;
    }
  }
  var runTimer = 0;
  function run() {
    runTimer = 0;
    filters.forEach(function (filter) {
      try {
        var nodes = [];
        for (var i = 0; i < filter.selector.length; i++) {
          nodes = step(nodes, filter.selector[i], i === 0);
          if (!nodes.length) return;
        }
        nodes.forEach(function (el) { act(el, filter.action); });
      } catch (e) {}
    });
  }
  function queueRun() {
    if (filters.length && !runTimer) runTimer = setTimeout(run, 200);
  }

  try {
    var options = { childList: true, subtree: true };
    if (generic) {
      options.attributes = true;
      options.attributeFilter = ['class', 'id'];
    }
    new MutationObserver(function (records) {
      for (var r = 0; r < records.length; r++) {
        var record = records[r];
        if (record.type === 'attributes') {
          if (generic) note(record.target);
        } else {
          for (var n = 0; n < record.addedNodes.length; n++) scan(record.addedNodes[n]);
        }
      }
      queueFlush(50);
      queueRun();
    }).observe(doc.documentElement, options);
    scan(doc.documentElement);
    queueFlush(0);
    doc.addEventListener('DOMContentLoaded', function () {
      flush();
      run();
    });
  } catch (e) {}
})
