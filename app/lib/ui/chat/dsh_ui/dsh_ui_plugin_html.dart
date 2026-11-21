// The Flutter side of the `html` plugin-view block
// (docs/design/native-plugins-views.md): a plugin hands over one HTML document
// and a height, and gets a WebView that draws whatever it wants — its own
// layout, CSS, canvas, SVG. This is the escape hatch for the components the
// native tree does not have.
//
// The document is its own island: it runs in an empty `about:blank` page, every
// navigation away is refused, and nothing in it can reach the app or the
// network. When the plugin declared `htmlScript`, the document's script runs
// and can call back through the injected `dshGo.invoke(action, args)` /
// `dshGo.resize(height)`; without that declaration the document is drawn with
// script switched off.

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../../l10n/l10n.dart';
import '../../theme/app_icons.dart';
import '../../theme/theme.dart';
import '../../web/web_view_guard.dart';
import 'dsh_ui_blocks.dart';
import 'dsh_ui_model.dart';

/// One `html` block: the plugin's document in a WebView of [DshUiHtml.height].
///
/// A document that fails to load falls back to one line saying so, at a third
/// of the height, so a broken block does not hold a big empty hole.
class DshUiHtmlBlock extends StatefulWidget {
  const DshUiHtmlBlock({super.key, required this.node});

  final DshUiHtml node;

  /// The hole a broken document leaves.
  static const brokenHeight = 44.0;

  @override
  State<DshUiHtmlBlock> createState() => DshUiHtmlBlockState();
}

class DshUiHtmlBlockState extends State<DshUiHtmlBlock> {
  WebViewController? _controller;
  late final WebViewGuard _guard = WebViewGuard(onGone: _onRendererGone);
  bool _failed = false;
  bool _gone = false;

  /// The height the document asked for through `dshGo.resize`, clamped to what
  /// the profile allows.
  double? _grown;

  double get _height => (_grown ?? widget.node.height).clamp(
    DshUiProfile.htmlHeightMin,
    DshUiProfile.htmlHeightMax,
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_controller == null) _open();
  }

  @override
  void didUpdateWidget(DshUiHtmlBlock old) {
    super.didUpdateWidget(old);
    if (old.node.html != widget.node.html || old.node.script != widget.node.script) _reload();
  }

  @override
  void dispose() {
    _guard.dispose();
    super.dispose();
  }

  Future<void> _open() async {
    final theme = Theme.of(context);
    final controller = WebViewController();
    _controller = controller;
    _failed = false;
    final script = widget.node.script;
    await Future.wait<void>(
      [
        controller.setJavaScriptMode(script ? JavaScriptMode.unrestricted : JavaScriptMode.disabled),
        controller.setBackgroundColor(Colors.transparent),
        controller.setNavigationDelegate(
          NavigationDelegate(
            // The document is the only page. Everything else — a link, a
            // redirect, a sub-frame — is refused: no navigation, no network.
            onNavigationRequest: (request) => _isSelf(request.url)
                ? NavigationDecision.navigate
                : NavigationDecision.prevent,
            onPageFinished: (_) {
              if (!mounted || _controller != controller) return;
              unawaited(_injectBridge(controller).catchError((Object _) {}));
            },
            onWebResourceError: (error) {
              if (!mounted || _controller != controller) return;
              if (error.isForMainFrame ?? true) setState(() => _failed = true);
            },
          ),
        ),
        if (script)
          controller.addJavaScriptChannel('DshGoPluginHtml', onMessageReceived: (m) => _onMessage(m.message)),
      ].map((f) => f.catchError((Object _) {})),
    );
    if (!mounted || _controller != controller) return;
    await _guard.attach(
      controller,
      dark: theme.brightness == Brightness.dark,
      textZoom: MediaQuery.textScalerOf(context).scale(100).round().clamp(50, 300),
    );
    if (!mounted || _controller != controller) return;
    await controller.loadHtmlString(_document(theme)).catchError((Object _) {
      if (mounted && _controller == controller) setState(() => _failed = true);
    });
  }

  /// `about:blank` and the `data:` document are ours; anything else is not.
  static bool _isSelf(String url) => url == 'about:blank' || url.startsWith('data:text/html');

  /// The plugin's document with the bridge prepended, the app's colours handed
  /// in as CSS variables, and a viewport so it fills the box.
  String _document(ThemeData theme) {
    final scheme = theme.colorScheme;
    final dsh = DshColors.of(context);
    String hex(Color c) => '#${(c.toARGB32() & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
    final vars =
        ':root{'
        '--dsh-bg:${hex(scheme.surface)};'
        '--dsh-surface:${hex(context.dshUiWell)};'
        '--dsh-text:${hex(scheme.onSurface)};'
        '--dsh-muted:${hex(dsh.textTertiary)};'
        '--dsh-accent:${hex(scheme.primary)};'
        '--dsh-on-accent:${hex(scheme.onPrimary)};'
        '--dsh-radius:14px;'
        '}';
    final head =
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<style>$vars'
        'html,body{margin:0;padding:0;background:transparent;color:var(--dsh-text);'
        'font-family:system-ui,-apple-system,"Noto Sans SC",sans-serif;'
        '-webkit-text-size-adjust:100%;overflow:hidden;}'
        '*{box-sizing:border-box;}'
        '</style>';
    final bridge = widget.node.script ? _bridgeScript : '';
    final body = widget.node.html;
    final lower = body.toLowerCase();
    if (lower.contains('<html')) {
      // A full document: put the head bits right after <head>, else after <html>.
      final at = lower.indexOf('<head');
      if (at >= 0) {
        final close = body.indexOf('>', at);
        if (close >= 0) return '${body.substring(0, close + 1)}$head$bridge${body.substring(close + 1)}';
      }
      final htmlAt = lower.indexOf('<html');
      final close = htmlAt >= 0 ? body.indexOf('>', htmlAt) : -1;
      if (close >= 0) return '${body.substring(0, close + 1)}$head$bridge${body.substring(close + 1)}';
      return '$head$bridge$body';
    }
    return '<!DOCTYPE html><html><head>$head$bridge</head><body>$body</body></html>';
  }

  /// The `dshGo` object the document talks to. Sends one JSON message per call;
  /// the Flutter side turns it into a plugin action.
  static const _bridgeScript = '''
<script>
(function(){
  function send(payload){
    try { DshGoPluginHtml.postMessage(JSON.stringify(payload)); } catch (e) {}
  }
  window.dshGo = {
    invoke: function(action, args){ send({kind:'invoke', action: String(action), args: args || {}}); },
    resize: function(height){ var h = Number(height); if (isFinite(h) && h > 0) send({kind:'resize', height: h}); },
    ready: function(){ send({kind:'ready'}); }
  };
  document.addEventListener('click', function(e){
    var a = e.target && e.target.closest ? e.target.closest('[data-action]') : null;
    if (!a) return;
    e.preventDefault();
    var raw = a.getAttribute('data-args');
    var args = {};
    if (raw) { try { args = JSON.parse(raw); } catch (err) { args = {}; } }
    window.dshGo.invoke(a.getAttribute('data-action'), args);
  }, true);
})();
</script>''';

  /// After every load the app's theme can have changed: re-apply the variables.
  Future<void> _injectBridge(WebViewController controller) async {
    if (!mounted || !widget.node.script) return;
    final scheme = Theme.of(context).colorScheme;
    final dsh = DshColors.of(context);
    String hex(Color c) => '#${(c.toARGB32() & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
    await controller.runJavaScript('''
(function(){
  var r = document.documentElement.style;
  r.setProperty('--dsh-bg','${hex(scheme.surface)}');
  r.setProperty('--dsh-text','${hex(scheme.onSurface)}');
  r.setProperty('--dsh-muted','${hex(dsh.textTertiary)}');
  r.setProperty('--dsh-accent','${hex(scheme.primary)}');
  r.setProperty('--dsh-on-accent','${hex(scheme.onPrimary)}');
})();
''');
  }

  void _onMessage(String raw) {
    // The document is untrusted: parse defensively, ignore anything unexpected.
    final Map<String, Object?> message;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return;
      message = decoded.map((k, v) => MapEntry(k.toString(), v));
    } on FormatException {
      return;
    }
    switch (message['kind']) {
      case 'invoke':
        final action = message['action'];
        if (action is! String || action.isEmpty || action.length > 64) return;
        final args = message['args'];
        final scope = DshUiPluginScope.maybeOf(context);
        if (scope == null || !scope.interactive) return;
        final mapped = args is Map ? args.map((k, v) => MapEntry(k.toString(), v)) : const <String, Object?>{};
        // The same path a button in the native tree takes: one plugin action.
        unawaited(Future<void>.sync(() => scope.onAction!(DshUiAction(action, mapped))));
        return;
      case 'resize':
        final height = message['height'];
        if (height is! num || !height.isFinite) return;
        final clamped = height.toDouble().clamp(DshUiProfile.htmlHeightMin, DshUiProfile.htmlHeightMax);
        if ((_grown ?? widget.node.height) == clamped) return;
        setState(() => _grown = clamped);
        return;
      case 'ready':
        return;
    }
  }

  void _onRendererGone(bool crashed) {
    if (_gone || !mounted) return;
    setState(() => _gone = true);
    WidgetsBinding.instance.addPostFrameCallback((_) => unawaited(_guard.destroy()));
  }

  void _reload() {
    setState(() {
      _gone = false;
      _grown = null;
    });
    _controller = null;
    unawaited(_guard.destroy().then((_) {
      if (mounted && _controller == null) _open();
    }));
  }

  @override
  Widget build(BuildContext context) {
    final dsh = DshColors.of(context);
    final theme = Theme.of(context);
    if (_failed || _gone) {
      return SizedBox(
        height: DshUiHtmlBlock.brokenHeight,
        child: Row(
          children: [
            Icon(AppIcons.info, size: 16, color: dsh.textTertiary),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                context.l10n.plugViewHtmlUnavailable,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(color: dsh.textTertiary),
              ),
            ),
            TextButton(onPressed: _reload, child: Text(context.l10n.commonRetry)),
          ],
        ),
      );
    }
    // The WebView is clipped to the block's corner radius; the document paints
    // its own background (transparent unless it sets one).
    return ClipRRect(
      borderRadius: BorderRadius.circular(DshUiProfile.htmlRadius),
      child: SizedBox(
        height: _height,
        width: double.infinity,
        child: _controller == null
            ? const SizedBox.shrink()
            : WebViewWidget(key: ObjectKey(_controller), controller: _controller!),
      ),
    );
  }
}
