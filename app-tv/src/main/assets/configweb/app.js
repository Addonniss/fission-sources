(function () {
  'use strict';

  var SESSION_HEADER = 'X-Infusion-Session';

  var ROW = {
    addon: 'addon',
    catalogDiscover: 'catalog_discover',
    installedOrder: 'installed_order',
    catalogOrder: 'catalog_order',
    widget: 'widget',
    widgetOrder: 'widget_order',
    homeCatalog: 'home_catalog',
    homeCatalogOrder: 'home_catalog_order',
    providerHidden: 'provider_hidden',
    pack: 'pack',
    collectionRow: 'row',
    collectionRowOrder: 'row_order'
  };

  var token = null;
  var snapshot = null;
  var draftId = null;
  var operations = [];
  var original = {};

  var orderEdits = {};
  var catalogOrderScope = null;
  var collectionOrderScope = null;
  var widgetAddDestination = null;
  var widgetAddKind = null;
  var widgetAddItem = null;
  var pollTimer = null;
  var pollDelay = 400;
  var pollActive = false;
  var remainingMs = 0;
  var countdownTimer = null;
  var activeTab = 'general';
  var tabs = [];
  var ended = false;

  var el = {};

  function $(id) { return document.getElementById(id); }

  function show(node, visible) {
    if (!node) return;
    if (visible) node.removeAttribute('hidden');
    else node.setAttribute('hidden', 'hidden');
  }

  function setError(message) {
    if (!el.appError) return;
    el.appError.textContent = message || '';
    show(el.appError, !!message);
  }

  function setBanner(message) {
    if (!el.banner) return;
    el.banner.textContent = message || '';
    show(el.banner, !!message);
  }

  function api(method, path, body) {
    var init = {
      method: method,
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      headers: {}
    };
    init.headers[SESSION_HEADER] = token;
    if (body !== undefined && body !== null) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    return fetch(path, init).then(function (response) {
      return response.text().then(function (text) {
        var parsed = null;
        try { parsed = text ? JSON.parse(text) : null; } catch (e) { parsed = null; }
        return { status: response.status, body: parsed };
      });
    });
  }

  function sessionLost(status) {
    stopPolling();
    stopCountdown();
    ended = true;
    if (status === 410) {
      setError('This session ended. Start a new one on the TV.');
    } else {
      setError('This session is no longer valid. Start a new one on the TV.');
    }
    show(el.app, false);
    show(el.gate, false);
    show(el.result, false);
  }

  function handleFailure(result, fallback) {
    if (result.status === 401 || result.status === 410) {
      sessionLost(result.status);
      return true;
    }
    if (result.status === 503) {
      setError('Phone setup is not available on this TV right now. Start again on the TV.');
      return true;
    }
    if (result.status === 429) {
      setError('Too many requests. Wait a moment, then try again.');
      return true;
    }
    setError(fallback);
    return false;
  }

  function normalizeCode(raw) {
    return String(raw || '').replace(/[\s\-_]+/g, '').toUpperCase();
  }

  function tryManualCode(raw) {
    var code = normalizeCode(raw);
    if (code.length !== 32) {
      showGateError('That code is 32 characters. Check it on the TV and try again.');
      return;
    }
    token = code;
    showGateError('');
    el.gateSubmit.disabled = true;
    api('GET', '/api/session').then(function (result) {
      el.gateSubmit.disabled = false;
      if (result.status === 200) {
        enterApp();
        return;
      }
      token = null;
      if (result.status === 401 || result.status === 410) {
        showGateError('That code was not accepted. Start a new session on the TV.');
        return;
      }
      if (result.status === 503) {
        showGateError('Phone setup is not available on this TV right now.');
        return;
      }
      showGateError('The TV did not answer. Check that it is on the same network.');
    }).catch(function () {
      el.gateSubmit.disabled = false;
      token = null;
      showGateError('Could not reach the TV. Check that it is on the same network.');
    });
  }

  function showGateError(message) {
    el.gateError.textContent = message || '';
    show(el.gateError, !!message);
  }

  function enterApp() {
    show(el.gate, false);
    show(el.app, true);
    setError('');
    loadState();
  }

  function loadState() {
    return api('GET', '/api/state').then(function (result) {
      if (result.status !== 200 || !result.body || result.body.status !== 'ok') {
        if (!handleFailure(result, 'The TV could not send its settings.')) {
          setError('The TV could not send its settings.');
        }
        return;
      }
      snapshot = result.body;
      original = {};
      orderEdits = {};
      operations = [];
      collectOriginals();
      renderTabs();
      render();
      updateReview();
    }).catch(function () {
      setError('Lost the connection to the TV.');
    });
  }

  function collectOriginals() {
    (snapshot.families || []).forEach(function (family) {
      (family.groups || []).forEach(function (group) {
        (group.rows || []).forEach(function (row) {
          original[family.family + '/' + row.key] = row.value;
        });
      });
    });
  }

  function rowValue(family, row) {
    var changed = operations.filter(function (op) {
      return op.family === family && op.kind === 'set_row' && op.rowKey === row.key;
    });
    if (changed.length) return lastSetRowValue(changed[changed.length - 1]);
    return row.value;
  }

  function lastSetRowValue(op) {
    var v = op.value || {};
    if (v.kind === 'flag') return String(v.flag);
    if (v.kind === 'number') return String(v.number);
    return v.text;
  }

  function renderTabs() {
    var order = ['general', 'playback', 'ratings', 'addons', 'home', 'collections'];
    var labels = {
      general: 'General', playback: 'Playback', ratings: 'Ratings',
      addons: 'Add-ons', home: 'Home', collections: 'Collections'
    };
    var available = {};
    (snapshot.families || []).forEach(function (f) { available[f.family] = true; });
    available.addons = true;
    available.home = true;
    available.collections = true;
    tabs = order.filter(function (id) { return available[id]; }).map(function (id) {
      return { id: id, label: labels[id] };
    });
    if (!tabs.some(function (t) { return t.id === activeTab; })) activeTab = tabs[0].id;
    el.tabs.textContent = '';
    tabs.forEach(function (tab) {
      var button = document.createElement('button');
      button.type = 'button';
      button.textContent = tab.label;
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-selected', tab.id === activeTab ? 'true' : 'false');
      button.addEventListener('click', function () {
        activeTab = tab.id;
        renderTabs();
        render();
      });
      el.tabs.appendChild(button);
    });
  }

  function render() {
    var panel = el.panels;
    panel.textContent = '';
    if (!snapshot) return;
    if (activeTab === 'addons') return renderAddons(panel);
    if (activeTab === 'home') return renderHome(panel);
    if (activeTab === 'collections') return renderCollections(panel);
    var family = (snapshot.families || []).filter(function (f) { return f.family === activeTab; })[0];
    if (!family) {
      panel.appendChild(emptyLine('Nothing to configure here.'));
      return;
    }
    (family.groups || []).forEach(function (group) {
      var box = document.createElement('div');
      box.className = 'group';
      var heading = document.createElement('h3');
      heading.textContent = group.name;
      box.appendChild(heading);
      (group.rows || []).forEach(function (row) {
        box.appendChild(preferenceRow(family.family, row));
      });
      panel.appendChild(box);
    });
  }

  function isActive(family, row) {
    if (row.active === false) return false;
    if (!row.parent) return true;
    var parent = null;
    (snapshot.families || []).forEach(function (f) {
      (f.groups || []).forEach(function (g) {
        (g.rows || []).forEach(function (r) {
          if (f.family === family && r.key === row.parent) parent = r;
        });
      });
    });
    if (!parent) return true;
    return rowValue(family, parent) === 'true';
  }

  function preferenceRow(family, row) {
    var wrapper = document.createElement('div');
    wrapper.className = 'row';
    var labelBox = document.createElement('div');
    labelBox.className = 'label';
    var title = document.createElement('span');
    title.className = 'title';
    title.textContent = row.label || row.key;
    labelBox.appendChild(title);
    if (row.scope === 'device') {
      var chip = document.createElement('span');
      chip.className = 'scope-chip';
      chip.textContent = 'This TV';
      chip.title = 'Device-wide: this changes the whole TV, not one profile.';
      labelBox.appendChild(chip);
    }
    if (row.help) {
      var sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = row.help;
      labelBox.appendChild(sub);
    }
    wrapper.appendChild(labelBox);

    if (!isActive(family, row)) {
      wrapper.className = 'row inactive';
      var note = document.createElement('span');
      note.className = 'sub';
      note.textContent = 'Off';
      wrapper.appendChild(note);
      return wrapper;
    }

    var control = document.createElement('div');
    control.className = 'control';
    var current = rowValue(family, row);

    if (row.control === 'flag') {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'switch';
      button.setAttribute('role', 'switch');
      button.setAttribute('aria-checked', current === 'true' ? 'true' : 'false');
      button.setAttribute('aria-label', row.label || row.key);
      button.addEventListener('click', function () {
        var next = button.getAttribute('aria-checked') !== 'true';
        button.setAttribute('aria-checked', next ? 'true' : 'false');
        setRow(family, row.key, { kind: 'flag', flag: next });
        markChanged(wrapper, family, row);
        render();
      });
      control.appendChild(button);
    } else if (row.control === 'choice') {
      var select = document.createElement('select');
      select.setAttribute('aria-label', row.label || row.key);
      (row.choices || []).forEach(function (choice) {
        var option = document.createElement('option');
        option.value = choice.token;
        option.textContent = choice.label || choice.token;
        if (choice.token === current) option.selected = true;
        select.appendChild(option);
      });
      select.addEventListener('change', function () {
        setRow(family, row.key, { kind: 'text', text: select.value });
        markChanged(wrapper, family, row);
      });
      control.appendChild(select);
    } else {
      var input = document.createElement('input');
      input.type = 'number';
      input.inputMode = 'numeric';
      input.setAttribute('aria-label', row.label || row.key);
      input.value = current;
      if (typeof row.min === 'number') input.min = String(row.min);
      if (typeof row.max === 'number') input.max = String(row.max);
      input.addEventListener('change', function () {
        var raw = Number(input.value);
        if (!isFinite(raw)) return;
        setRow(family, row.key, { kind: 'number', number: Math.round(raw) });
        markChanged(wrapper, family, row);
      });
      control.appendChild(input);
    }
    wrapper.appendChild(control);
    markChanged(wrapper, family, row);
    return wrapper;
  }

  function markChanged(wrapper, family, row) {
    var dirty = rowValue(family, row) !== original[family + '/' + row.key];
    if (dirty) wrapper.classList.add('changed');
    else wrapper.classList.remove('changed');
  }

  function setRow(family, rowKey, value) {
    operations = operations.filter(function (op) {
      return !(op.family === family && op.kind === 'set_row' && op.rowKey === rowKey);
    });
    if (value.kind === 'flag') {
      if (String(value.flag) === original[family + '/' + rowKey]) { updateReview(); return; }
    } else if (value.kind === 'text') {
      if (value.text === original[family + '/' + rowKey]) { updateReview(); return; }
    } else if (value.kind === 'number') {
      if (String(value.number) === original[family + '/' + rowKey]) { updateReview(); return; }
    }
    operations.push({ family: family, kind: 'set_row', rowKey: rowKey, value: value });
    updateReview();
  }

  function renderAddons(panel) {
    var addons = snapshot.addons || [];
    var group = document.createElement('div');
    group.className = 'group';
    var heading = document.createElement('h3');
    heading.textContent = 'Installed add-ons';
    group.appendChild(heading);

    if (!addons.length) group.appendChild(emptyLine('No add-ons are installed.'));

    addons.forEach(function (addon) {
      var row = document.createElement('div');
      row.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var title = document.createElement('span');
      title.className = 'title';
      title.textContent = addon.name || addon.addonId;
      labelBox.appendChild(title);
      var sub = document.createElement('span');
      sub.className = 'sub';

      sub.textContent = (addon.origin || 'link hidden') + (addon.enabled ? '' : ' · disabled');
      labelBox.appendChild(sub);
      row.appendChild(labelBox);

      var actions = document.createElement('div');
      actions.className = 'item-actions';

      var toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.textContent = addon.enabled ? 'Disable' : 'Enable';
      toggle.addEventListener('click', function () {
        addItem({
          family: 'addons',
          kind: addon.enabled ? 'disable' : 'enable',
          rowKey: ROW.addon,
          targetId: addon.addonId
        });
      });
      actions.appendChild(toggle);

      if (addon.managed) {
        var remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'danger';
        remove.textContent = 'Remove';
        remove.addEventListener('click', function () {
          addItem({ family: 'addons', kind: 'remove', rowKey: ROW.addon, targetId: addon.addonId });
        });
        actions.appendChild(remove);
      }
      row.appendChild(actions);
      group.appendChild(row);

      (addon.catalogs || []).forEach(function (catalog) {
        group.appendChild(discoverRow(addon, catalog));
      });
    });
    panel.appendChild(group);

    var add = document.createElement('div');
    add.className = 'group';
    var addHeading = document.createElement('h3');
    addHeading.textContent = 'Add an add-on';
    add.appendChild(addHeading);
    var form = document.createElement('div');
    form.className = 'add-form';
    var url = document.createElement('input');
    url.type = 'url';
    url.placeholder = 'https://host/manifest.json';
    url.setAttribute('aria-label', 'Add-on manifest URL');
    var submit = document.createElement('button');
    submit.type = 'button';
    submit.textContent = 'Add';
    submit.addEventListener('click', function () {
      var value = url.value.trim();
      if (!value) return;
      addItem({
        family: 'addons',
        kind: 'add',
        rowKey: ROW.addon,
        value: { kind: 'text', text: value }
      });
      url.value = '';
    });
    form.appendChild(url);
    form.appendChild(submit);
    add.appendChild(form);
    var note = document.createElement('p');
    note.className = 'help';
    note.textContent = 'The add-on is reviewed and installed only after you confirm on the TV.';
    add.appendChild(note);
    panel.appendChild(add);

    panel.appendChild(orderGroup(
      'Order',
      addons.map(function (a) { return { id: a.addonId, label: a.name || a.addonId }; }),
      'addons',
      ROW.installedOrder,
      'addons|installed'
    ));

    panel.appendChild(catalogOrderGroup(addons));
  }

  function catalogOrderGroup(addons) {
    var sortable = addons.filter(function (a) { return (a.catalogs || []).length > 1; });
    var box = document.createElement('div');
    box.className = 'group';
    var heading = document.createElement('h3');
    heading.textContent = 'Discover order';
    box.appendChild(heading);
    var hint = document.createElement('p');
    hint.className = 'help';
    hint.textContent = 'Only one add-on\u2019s catalog order can be in a review at a time.';
    box.appendChild(hint);
    if (!sortable.length) {
      box.appendChild(emptyLine('No add-on has more than one catalog.'));
      return box;
    }
    var selected = sortable.filter(function (a) { return a.addonId === catalogOrderScope; })[0] || sortable[0];
    catalogOrderScope = selected.addonId;
    box.appendChild(scopeSelect(
      'Add-on',
      sortable.map(function (a) { return { id: a.addonId, label: a.name || a.addonId }; }),
      selected.addonId,
      function (value) { catalogOrderScope = value; render(); }
    ));
    var items = (selected.catalogs || []).slice()
      .sort(function (a, b) { return a.position - b.position; })
      .map(function (catalog) {
        return { id: catalogIdentity(selected.addonId, catalog), label: catalog.name || catalog.catalogId };
      });
    var scopeKey = 'addons|catalog|' + selected.addonId;
    if (items.length < 2) {
      box.appendChild(emptyLine('This add-on has one catalog, so there is nothing to reorder.'));
      return box;
    }
    box.appendChild(orderList(items, 'addons', ROW.catalogOrder, scopeKey));
    if (orderEditExcluded(scopeKey, 'addons', ROW.catalogOrder)) {
      box.appendChild(emptyLine('Not in this review: another add-on\u2019s catalog order is.'));
    }
    return box;
  }

  function discoverRow(addon, catalog) {
    var row = document.createElement('div');
    row.className = 'row';
    var labelBox = document.createElement('div');
    labelBox.className = 'label';
    var title = document.createElement('span');
    title.className = 'title';
    title.textContent = catalog.name || catalog.catalogId;
    labelBox.appendChild(title);
    var sub = document.createElement('span');
    sub.className = 'sub';
    sub.textContent = 'Show in Discover';
    labelBox.appendChild(sub);
    row.appendChild(labelBox);
    var actions = document.createElement('div');
    actions.className = 'item-actions';
    var toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.textContent = catalog.visibleInDiscover ? 'Hide' : 'Show';
    toggle.addEventListener('click', function () {
      addItem({
        family: 'addons',
        kind: catalog.visibleInDiscover ? 'disable' : 'enable',
        rowKey: ROW.catalogDiscover,
        targetId: catalogIdentity(addon.addonId, catalog)
      });
    });
    actions.appendChild(toggle);
    row.appendChild(actions);
    return row;
  }

  function catalogIdentity(addonId, catalog) {
    return [addonId, catalog.catalogType, catalog.catalogId].join('|');
  }

  function renderHome(panel) {
    var home = snapshot.home || { catalogs: [], widgets: [] };

    var catalogs = document.createElement('div');
    catalogs.className = 'group';
    var catalogsHeading = document.createElement('h3');
    catalogsHeading.textContent = 'Home catalogs';
    catalogs.appendChild(catalogsHeading);
    if (!(home.catalogs || []).length) catalogs.appendChild(emptyLine('No Home catalogs.'));
    (home.catalogs || []).forEach(function (catalog) {
      var row = document.createElement('div');
      row.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var title = document.createElement('span');
      title.className = 'title';
      title.textContent = catalog.name || catalog.catalogId;
      labelBox.appendChild(title);
      var sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = catalog.selected
        ? (catalog.hiddenFromHome ? 'On the Home bar, hidden' : 'On the Home bar')
        : 'Not on the Home bar';
      labelBox.appendChild(sub);
      row.appendChild(labelBox);
      var actions = document.createElement('div');
      actions.className = 'item-actions';
      var toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.textContent = catalog.selected ? 'Remove' : 'Add';
      toggle.addEventListener('click', function () {
        var identity = catalogIdentity(catalog.addonId, catalog);
        if (catalog.selected) {
          addItem({ family: 'home', kind: 'remove', rowKey: ROW.homeCatalog, targetId: identity });
        } else {
          addItem({
            family: 'home',
            kind: 'add',
            rowKey: ROW.homeCatalog,
            value: { kind: 'text', text: identity }
          });
        }
      });
      actions.appendChild(toggle);
      row.appendChild(actions);
      catalogs.appendChild(row);
    });
    panel.appendChild(catalogs);

    panel.appendChild(orderGroup(
      'Home bar order',
      (home.catalogs || [])
        .filter(function (catalog) { return catalog.selected; })
        .slice()
        .sort(function (a, b) { return a.position - b.position; })
        .map(function (catalog) {
          return { id: catalogIdentity(catalog.addonId, catalog), label: catalog.name || catalog.catalogId };
        }),
      'home',
      ROW.homeCatalogOrder,
      'home|catalogs'
    ));

    panel.appendChild(providerHiddenGroup(home));

    var widgets = document.createElement('div');
    widgets.className = 'group';
    var widgetsHeading = document.createElement('h3');
    widgetsHeading.textContent = 'Home rows';
    widgets.appendChild(widgetsHeading);
    if (!(home.widgets || []).length) widgets.appendChild(emptyLine('No Home rows.'));
    (home.widgets || []).forEach(function (widget) {
      var row = document.createElement('div');
      row.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var title = document.createElement('span');
      title.className = 'title';
      title.textContent = widget.label || widget.widgetId;
      labelBox.appendChild(title);
      row.appendChild(labelBox);
      var actions = document.createElement('div');
      actions.className = 'item-actions';
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'danger';
      remove.textContent = 'Delete';
      remove.addEventListener('click', function () {
        addItem({ family: 'home', kind: 'remove', rowKey: ROW.widget, targetId: widget.widgetId });
      });
      actions.appendChild(remove);
      row.appendChild(actions);
      widgets.appendChild(row);
    });
    panel.appendChild(widgets);

    panel.appendChild(widgetAddGroup(home));

    panel.appendChild(orderGroup(
      'Row order',
      (home.widgets || []).map(function (w) { return { id: w.widgetId, label: w.label || w.widgetId }; }),
      'home',
      ROW.widgetOrder,
      'home|widgets'
    ));
  }

  function providerHiddenGroup(home) {
    var providers = [];
    (home.catalogs || []).forEach(function (catalog) {
      if (providers.some(function (p) { return p.id === catalog.addonId; })) return;
      providers.push({
        id: catalog.addonId,
        name: addonName(catalog.addonId),
        hidden: !!catalog.hiddenFromHome
      });
    });
    var box = document.createElement('div');
    box.className = 'group';
    var heading = document.createElement('h3');
    heading.textContent = 'Providers on Home';
    box.appendChild(heading);
    if (!providers.length) {
      box.appendChild(emptyLine('No provider offers a Home catalog.'));
      return box;
    }
    providers.forEach(function (provider) {
      var row = document.createElement('div');
      row.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var title = document.createElement('span');
      title.className = 'title';
      title.textContent = provider.name;
      labelBox.appendChild(title);
      var sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = provider.hidden ? 'Hidden from Home' : 'On Home';
      labelBox.appendChild(sub);
      row.appendChild(labelBox);
      var actions = document.createElement('div');
      actions.className = 'item-actions';
      var toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.textContent = provider.hidden ? 'Show on Home' : 'Hide from Home';
      toggle.addEventListener('click', function () {
        addItem({
          family: 'home',
          kind: provider.hidden ? 'enable' : 'disable',
          rowKey: ROW.providerHidden,
          targetId: provider.id
        });
      });
      actions.appendChild(toggle);
      row.appendChild(actions);
      box.appendChild(row);
    });
    return box;
  }

  function addonName(addonId) {
    var match = (snapshot.addons || []).filter(function (a) { return a.addonId === addonId; })[0];
    return match && match.name ? match.name : addonId;
  }

  var COLLECTION_ARM_MARKER = 'page';

  function widgetSourceText(destinationId, kind, itemId) {
    var parts = String(itemId).split('|');
    if (kind === 'addon') {
      return [destinationId, 'addon', parts[0], parts[1], parts[2]].join('|');
    }
    return [destinationId, 'collection', parts[0], parts[1], COLLECTION_ARM_MARKER].join('|');
  }

  function pendingWidgetAdd() {
    var found = null;
    operations.forEach(function (op) {
      if (op.family === 'home' && op.kind === 'add' && op.rowKey === ROW.widget) found = op;
    });
    return found;
  }

  function widgetAddGroup(home) {
    var destinations = home.widgetDestinations || [];
    var catalogs = home.widgetCatalogs || [];
    var groups = home.widgetCollectionGroups || [];

    var box = document.createElement('div');
    box.className = 'group';
    var heading = document.createElement('h3');
    heading.textContent = 'Add a Home row';
    box.appendChild(heading);

    if (!destinations.length) {
      box.appendChild(emptyLine('This TV offers no Home destination for a created row.'));
      return box;
    }
    var kinds = [];
    if (catalogs.length) kinds.push({ id: 'addon', label: 'Add-on catalog' });
    if (groups.length) kinds.push({ id: 'collection', label: 'Collection page' });
    if (!kinds.length) {
      box.appendChild(emptyLine('No catalog or collection page is available to add.'));
      return box;
    }

    var destination = destinations.filter(function (d) { return d.id === widgetAddDestination; })[0] || destinations[0];
    widgetAddDestination = destination.id;
    var kind = kinds.filter(function (k) { return k.id === widgetAddKind; })[0] || kinds[0];
    widgetAddKind = kind.id;

    var sources = (kind.id === 'addon')
      ? catalogs.map(function (c) {
        return { id: [c.addonId, c.catalogType, c.catalogId].join('|'), label: c.name || c.catalogId };
      })
      : groups.map(function (g) {
        return { id: [g.packId, g.groupKey].join('|'), label: g.name || g.groupKey };
      });
    var source = sources.filter(function (s) { return s.id === widgetAddItem; })[0] || sources[0];
    widgetAddItem = source.id;

    box.appendChild(scopeSelect(
      'Destination',
      destinations.map(function (d) { return { id: d.id, label: d.label || d.id }; }),
      destination.id,
      function (value) { widgetAddDestination = value; render(); }
    ));
    if (kinds.length > 1) {
      box.appendChild(scopeSelect(
        'Source',
        kinds,
        kind.id,
        function (value) { widgetAddKind = value; widgetAddItem = null; render(); }
      ));
    }
    box.appendChild(scopeSelect(
      kind.id === 'addon' ? 'Catalog' : 'Collection page',
      sources,
      source.id,
      function (value) { widgetAddItem = value; render(); }
    ));

    var add = document.createElement('button');
    add.type = 'button';
    add.textContent = 'Add Home row';
    add.addEventListener('click', function () {
      addItem({
        family: 'home',
        kind: 'add',
        rowKey: ROW.widget,
        value: { kind: 'text', text: widgetSourceText(destination.id, kind.id, source.id) }
      });
      render();
    });
    box.appendChild(add);

    var pending = pendingWidgetAdd();
    if (pending) {
      var line = document.createElement('div');
      line.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var title = document.createElement('span');
      title.className = 'title';
      title.textContent = 'Queued: add ' + source.label;
      labelBox.appendChild(title);
      line.appendChild(labelBox);
      var actions = document.createElement('div');
      actions.className = 'item-actions';
      var discard = document.createElement('button');
      discard.type = 'button';
      discard.textContent = 'Discard';
      discard.addEventListener('click', function () {
        operations = operations.filter(function (op) {
          return !(op.family === 'home' && op.kind === 'add' && op.rowKey === ROW.widget);
        });
        updateReview();
        render();
      });
      actions.appendChild(discard);
      line.appendChild(actions);
      box.appendChild(line);
    }

    var note = document.createElement('p');
    note.className = 'help';
    note.textContent = 'The row is created only after you confirm on the TV.';
    box.appendChild(note);
    return box;
  }

  function renderCollections(panel) {
    var collections = snapshot.collections || { packs: [], pages: [] };

    var packs = document.createElement('div');
    packs.className = 'group';
    var packsHeading = document.createElement('h3');
    packsHeading.textContent = 'Collection packs';
    packs.appendChild(packsHeading);
    if (!(collections.packs || []).length) packs.appendChild(emptyLine('No collection packs.'));
    (collections.packs || []).forEach(function (pack) {
      var row = document.createElement('div');
      row.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var title = document.createElement('span');
      title.className = 'title';
      title.textContent = pack.name || pack.packId;
      labelBox.appendChild(title);
      var sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = (pack.origin || 'link hidden') + ' · ' + pack.groupCount +
        ' groups, ' + pack.tileCount + ' rows';
      labelBox.appendChild(sub);
      row.appendChild(labelBox);
      var actions = document.createElement('div');
      actions.className = 'item-actions';
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'danger';
      remove.textContent = 'Remove';
      remove.addEventListener('click', function () {
        addItem({ family: 'collections', kind: 'remove', rowKey: ROW.pack, targetId: pack.packId });
      });
      actions.appendChild(remove);
      row.appendChild(actions);
      packs.appendChild(row);
    });
    panel.appendChild(packs);

    var importBox = document.createElement('div');
    importBox.className = 'group';
    var importHeading = document.createElement('h3');
    importHeading.textContent = 'Import a collection pack';
    importBox.appendChild(importHeading);
    var form = document.createElement('div');
    form.className = 'add-form';
    var url = document.createElement('input');
    url.type = 'url';
    url.placeholder = 'https://host/pack.json';
    url.setAttribute('aria-label', 'Collection pack URL');
    var submit = document.createElement('button');
    submit.type = 'button';
    submit.textContent = 'Import';
    submit.addEventListener('click', function () {
      var value = url.value.trim();
      if (!value) return;
      addItem({
        family: 'collections',
        kind: 'import',
        rowKey: ROW.pack,
        value: { kind: 'text', text: value }
      });
      url.value = '';
    });
    form.appendChild(url);
    form.appendChild(submit);
    importBox.appendChild(form);
    var note = document.createElement('p');
    note.className = 'help';
    note.textContent = 'The pack is downloaded only after you confirm on the TV.';
    importBox.appendChild(note);
    panel.appendChild(importBox);

    panel.appendChild(collectionOrderGroup(collections));

    (collections.pages || []).forEach(function (page) {
      var group = document.createElement('div');
      group.className = 'group';
      var heading = document.createElement('h3');
      heading.textContent = page.title || page.tileKey;
      group.appendChild(heading);
      (page.rows || []).forEach(function (row) {
        var line = document.createElement('div');
        line.className = 'row';
        var labelBox = document.createElement('div');
        labelBox.className = 'label';
        var title = document.createElement('span');
        title.className = 'title';
        title.textContent = row.title || row.rowKey;
        labelBox.appendChild(title);
        line.appendChild(labelBox);
        var actions = document.createElement('div');
        actions.className = 'item-actions';
        var toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.textContent = row.enabled ? 'Hide' : 'Show';
        toggle.addEventListener('click', function () {
          addItem({
            family: 'collections',
            kind: row.enabled ? 'disable' : 'enable',
            rowKey: ROW.collectionRow,
            targetId: [page.packId, page.groupKey, page.tileKey, row.rowKey].join('|')
          });
        });
        actions.appendChild(toggle);
        line.appendChild(actions);
        group.appendChild(line);
      });
      if (page.truncated) group.appendChild(emptyLine('More rows exist than are shown here.'));
      panel.appendChild(group);
    });
  }

  function collectionOrderGroup(collections) {
    var pages = (collections.pages || []).filter(function (page) {
      return (page.rows || []).length > 1;
    });
    var box = document.createElement('div');
    box.className = 'group';
    var heading = document.createElement('h3');
    heading.textContent = 'Page row order';
    box.appendChild(heading);
    var hint = document.createElement('p');
    hint.className = 'help';
    hint.textContent = 'Only one page\u2019s row order can be in a review at a time.';
    box.appendChild(hint);
    if (!pages.length) {
      box.appendChild(emptyLine('No collection page has more than one row.'));
      return box;
    }
    var pageId = function (page) { return [page.packId, page.groupKey, page.tileKey].join('|'); };
    var selected = pages.filter(function (page) { return pageId(page) === collectionOrderScope; })[0] || pages[0];
    collectionOrderScope = pageId(selected);
    box.appendChild(scopeSelect(
      'Page',
      pages.map(function (page) {
        return { id: pageId(page), label: (page.title || page.tileKey) + ' \u00b7 ' + page.packId };
      }),
      collectionOrderScope,
      function (value) { collectionOrderScope = value; render(); }
    ));
    var items = (selected.rows || []).slice()
      .sort(function (a, b) { return a.position - b.position; })
      .map(function (row) {
        return {
          id: [selected.packId, selected.groupKey, selected.tileKey, row.rowKey].join('|'),
          label: row.title || row.rowKey
        };
      });
    var scopeKey = 'collections|page|' + collectionOrderScope;
    box.appendChild(orderList(items, 'collections', ROW.collectionRowOrder, scopeKey));
    if (orderEditExcluded(scopeKey, 'collections', ROW.collectionRowOrder)) {
      box.appendChild(emptyLine('Not in this review: another page\u2019s row order is.'));
    }
    return box;
  }

  function orderGroup(title, items, family, rowKey, scopeKey, help) {
    var box = document.createElement('div');
    box.className = 'group';
    if (title) {
      var heading = document.createElement('h3');
      heading.textContent = title;
      box.appendChild(heading);
    }
    if (help) {
      var hint = document.createElement('p');
      hint.className = 'help';
      hint.textContent = help;
      box.appendChild(hint);
    }
    if (items.length < 2) {
      box.appendChild(emptyLine('Nothing to reorder.'));
      return box;
    }
    box.appendChild(orderList(items, family, rowKey, scopeKey));
    if (orderEditExcluded(scopeKey, family, rowKey)) {
      box.appendChild(emptyLine('Not in this review: another list for this section is.'));
    }
    return box;
  }

  function orderList(items, family, rowKey, scopeKey) {
    var box = document.createElement('div');
    var current = completeOrder(items, scopeKey);
    current.forEach(function (id, index) {
      var item = items.filter(function (i) { return i.id === id; })[0] || { id: id, label: id };
      var row = document.createElement('div');
      row.className = 'row';
      var labelBox = document.createElement('div');
      labelBox.className = 'label';
      var name = document.createElement('span');
      name.className = 'title';
      name.textContent = (index + 1) + '. ' + item.label;
      labelBox.appendChild(name);
      row.appendChild(labelBox);
      var actions = document.createElement('div');
      actions.className = 'item-actions';
      var up = document.createElement('button');
      up.type = 'button';
      up.textContent = 'Up';
      up.disabled = index === 0;
      up.addEventListener('click', function () { move(family, rowKey, current, index, -1, scopeKey); });
      var down = document.createElement('button');
      down.type = 'button';
      down.textContent = 'Down';
      down.disabled = index === current.length - 1;
      down.addEventListener('click', function () { move(family, rowKey, current, index, 1, scopeKey); });
      actions.appendChild(up);
      actions.appendChild(down);
      row.appendChild(actions);
      box.appendChild(row);
    });
    return box;
  }

  function completeOrder(items, scopeKey) {
    var ids = items.map(function (i) { return i.id; });
    var edited = (orderEdits[scopeKey] || []).filter(function (id) { return ids.indexOf(id) >= 0; });
    return edited.concat(ids.filter(function (id) { return edited.indexOf(id) < 0; }));
  }

  function move(family, rowKey, ids, index, delta, scopeKey) {
    var target = index + delta;
    if (target < 0 || target >= ids.length) return;
    var next = ids.slice();
    var moved = next.splice(index, 1)[0];
    next.splice(target, 0, moved);
    orderEdits[scopeKey] = next;

    addItem({ family: family, kind: 'reorder', rowKey: rowKey, value: { kind: 'order', ids: next } }, true);
    render();
  }

  function pendingOrderIds(family, rowKey) {
    var found = null;
    operations.forEach(function (op) {
      if (op.family === family && op.kind === 'reorder' && op.rowKey === rowKey) found = op;
    });
    return found && found.value && found.value.kind === 'order' ? found.value.ids : null;
  }

  function scopeOfOrder(ids, rowKey) {
    var first = ids && ids.length ? String(ids[0]) : '';
    if (rowKey === ROW.catalogOrder) return 'addons|catalog|' + first.split('|')[0];
    if (rowKey === ROW.collectionRowOrder) return 'collections|page|' + first.split('|').slice(0, 3).join('|');
    return null;
  }

  function orderEditExcluded(scopeKey, family, rowKey) {
    if (!orderEdits[scopeKey]) return false;
    var pending = pendingOrderIds(family, rowKey);
    if (!pending) return true;
    var scope = scopeOfOrder(pending, rowKey);
    if (scope === null) return false;
    return scope !== scopeKey;
  }

  function scopeSelect(labelText, options, selectedId, onChange) {
    var row = document.createElement('div');
    row.className = 'row';
    var labelBox = document.createElement('div');
    labelBox.className = 'label';
    var title = document.createElement('span');
    title.className = 'title';
    title.textContent = labelText;
    labelBox.appendChild(title);
    row.appendChild(labelBox);
    var control = document.createElement('div');
    control.className = 'control wide';
    var select = document.createElement('select');
    select.setAttribute('aria-label', labelText);
    options.forEach(function (option) {
      var node = document.createElement('option');
      node.value = option.id;
      node.textContent = option.label;
      if (option.id === selectedId) node.selected = true;
      select.appendChild(node);
    });
    select.addEventListener('change', function () { onChange(select.value); });
    control.appendChild(select);
    row.appendChild(control);
    return row;
  }

  function addItem(operation, replaceOrder) {
    var key = operation.family + '|' + operation.kind + '|' + operation.rowKey + '|' +
      (operation.targetId || '');
    operations = operations.filter(function (op) {
      var opKey = op.family + '|' + op.kind + '|' + op.rowKey + '|' + (op.targetId || '');
      if (opKey === key) return false;

      if (replaceOrder && op.family === operation.family && op.kind === 'reorder' &&
          op.rowKey === operation.rowKey) return false;
      return true;
    });
    operations.push(operation);
    updateReview();
  }

  function emptyLine(text) {
    var p = document.createElement('p');
    p.className = 'empty';
    p.textContent = text;
    return p;
  }

  function updateReview() {
    if (!el.review) return;
    el.review.disabled = operations.length === 0 || !!draftId;
    el.dirty.textContent = operations.length === 0
      ? 'No changes yet'
      : (operations.length === 1 ? '1 change' : operations.length + ' changes');
  }

  function submitDraft() {
    if (!operations.length) return;
    setError('');
    setBanner('');
    el.review.disabled = true;
    el.review.textContent = 'Sending…';
    api('POST', '/api/draft', { operations: operations }).then(function (created) {
      if (created.status !== 200 || !created.body) {
        el.review.textContent = 'Review changes';
        if (created.status === 409) {
          setError('The TV is already waiting on a confirmation. Finish it on the TV first.');
          return;
        }
        if (created.status === 400) {
          setError('The TV refused a change: ' + describeField(created.body) + '.');
          return;
        }
        handleFailure(created, 'The TV did not accept these changes.');
        return;
      }
      draftId = created.body.draftId;
      api('POST', '/api/draft/' + encodeURIComponent(draftId) + '/submit', {}).then(function (sent) {
        el.review.textContent = 'Review changes';
        if (sent.status === 409) {
          setError('The TV’s settings changed while you were editing. Reload and review again.');
          draftId = null;
          updateReview();
          return;
        }
        if (sent.status !== 200) {
          draftId = null;
          updateReview();
          handleFailure(sent, 'The TV did not take this submission.');
          return;
        }
        showResult({ state: 'pending_confirmation', operations: [] });
        startPolling();
      }).catch(function () {
        el.review.textContent = 'Review changes';
        draftId = null;
        updateReview();
        setError('Lost the connection to the TV.');
      });
    }).catch(function () {
      el.review.textContent = 'Review changes';
      updateReview();
      setError('Lost the connection to the TV.');
    });
  }

  function describeField(body) {
    if (!body) return 'unknown reason';
    var field = body.field ? String(body.field) : '';
    var reason = body.reason ? String(body.reason) : 'rejected';
    return field ? field + ': ' + reason : reason;
  }

  var LIVE_STATES = {
    editing: true,
    submitted: true,
    applying: true
  };

  function isLiveState(state) { return LIVE_STATES[state] === true; }

  function isWaitingView(state) {
    return state === 'pending_confirmation' || isLiveState(state);
  }

  function startPolling() {
    stopPolling();
    pollActive = true;
    pollDelay = 400;
    schedulePoll();
  }

  function schedulePoll() {
    if (!pollActive || !draftId) return;
    if (document.visibilityState === 'hidden') return;
    pollTimer = window.setTimeout(pollOnce, pollDelay);
  }

  function pollOnce() {
    if (!pollActive || !draftId) return;
    api('GET', '/api/draft/' + encodeURIComponent(draftId)).then(function (result) {
      if (!pollActive) return;
      if (result.status !== 200 || !result.body) {
        if (result.status === 401 || result.status === 410) {
          sessionLost(result.status);
          return;
        }
        pollDelay = Math.min(Math.round(pollDelay * 1.5), 1000);
        schedulePoll();
        return;
      }
      var state = result.body.status;
      if (isLiveState(state)) {
        pollDelay = Math.min(Math.round(pollDelay * 1.5), 1000);
        schedulePoll();
        return;
      }
      stopPolling();
      showResult(result.body);
    }).catch(function () {
      if (!pollActive) return;
      pollDelay = Math.min(Math.round(pollDelay * 1.5), 1000);
      schedulePoll();
    });
  }

  function stopPolling() {
    pollActive = false;
    if (pollTimer) {
      window.clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  var STATE_TITLES = {
    pending_confirmation: 'Waiting for your TV',
    editing: 'Not sent yet',
    submitted: 'Waiting for your TV',
    applying: 'Applying on the TV',
    applied: 'Applied on the TV',
    partially_applied: 'Some changes were applied',
    failed: 'Finished with problems',
    stale: 'The TV changed first',
    rejected: 'Cancelled on the TV',
    expired: 'Session expired',
    session_ended: 'Session ended',
    ended: 'Session ended'
  };

  var STATE_NOTES = {
    pending_confirmation: 'Review the summary on your TV and confirm there. Nothing has changed yet.',
    editing: 'This draft has not been sent to the TV yet. Review your changes and send them again.',
    submitted: 'Your changes are on the TV, waiting to be reviewed there. Nothing has changed yet.',
    applying: 'The TV is applying your changes now. This page updates by itself when it finishes.',
    applied: 'Every change was applied.',
    partially_applied: 'Only the changes marked Applied were written. The rest were not applied, ' +
      'and nothing that was written was rolled back.',
    failed: 'Some changes were not applied. The list below is exactly what happened.',
    stale: 'The TV’s settings changed before your changes reached it, so nothing was applied.',
    rejected: 'Nothing was changed.',
    expired: 'Start a new session on the TV.',
    session_ended: 'Start a new session on the TV.',
    ended: 'Start a new session on the TV.'
  };

  function showResult(body) {
    show(el.app, false);
    show(el.result, true);
    var state = body && body.status ? String(body.status) : 'pending_confirmation';
    el.resultTitle.textContent = STATE_TITLES[state] || 'Result';
    el.resultNote.textContent = STATE_NOTES[state] || '';

    el.resultList.textContent = '';
    var list = (body && body.operations) || [];
    list.forEach(function (operation) {
      var item = document.createElement('li');
      var badge = document.createElement('span');
      badge.className = 'state ' + (operation.outcome || 'pending');
      badge.textContent = labelForOutcome(operation.outcome);
      item.appendChild(badge);
      var text = document.createElement('span');
      text.textContent = operation.family + ' · ' + (operation.rowKey || '');
      if (operation.reason) {
        var why = document.createElement('span');
        why.className = 'why';
        why.textContent = operation.reason;
        text.appendChild(why);
      }
      item.appendChild(text);
      el.resultList.appendChild(item);
    });

    var again = !isWaitingView(state);
    show(el.resultAgain, again);
    if (!again) startPolling();
  }

  function labelForOutcome(outcome) {
    if (outcome === 'applied') return 'Applied';
    if (outcome === 'failed') return 'Failed';
    if (outcome === 'not_attempted') return 'Not applied';
    return 'Pending';
  }

  function startCountdown() {
    stopCountdown();
    countdownTimer = window.setInterval(tickCountdown, 1000);
  }

  function tickCountdown() {
    if (ended) return;
    remainingMs -= 1000;
    if (remainingMs <= 0) {
      remainingMs = 0;
      el.remaining.textContent = 'Expired';
      stopCountdown();
      sessionLost(410);
      return;
    }
    var seconds = Math.floor(remainingMs / 1000);
    var minutes = Math.floor(seconds / 60);
    el.remaining.textContent = minutes + ':' + String(seconds % 60).padStart(2, '0') + ' left';
  }

  function stopCountdown() {
    if (countdownTimer) {
      window.clearInterval(countdownTimer);
      countdownTimer = null;
    }
  }

  function readFragmentToken() {
    var hash = window.location.hash || '';
    if (!hash) return null;
    var raw = hash.charAt(0) === '#' ? hash.slice(1) : hash;
    if (!raw) return null;
    var cleaned = normalizeCode(raw);
    try {
      window.history.replaceState(null, document.title, window.location.pathname);
    } catch (e) {

    }
    return cleaned.length === 32 ? cleaned : null;
  }

  function bind() {
    el = {
      gate: $('gate'),
      gateForm: $('gate-form'),
      gateSubmit: $('gate-submit'),
      gateError: $('gate-error'),
      code: $('code'),
      app: $('app'),
      appError: $('app-error'),
      banner: $('banner'),
      tabs: $('tabs'),
      panels: $('panels'),
      review: $('review'),
      dirty: $('dirty'),
      end: $('end'),
      remaining: $('remaining'),
      result: $('result'),
      resultTitle: $('result-title'),
      resultNote: $('result-note'),
      resultList: $('result-list'),
      resultAgain: $('result-again')
    };

    el.gateForm.addEventListener('submit', function (event) {
      event.preventDefault();
      tryManualCode(el.code.value);
    });

    el.review.addEventListener('click', submitDraft);

    el.end.addEventListener('click', function () {
      stopPolling();
      stopCountdown();
      ended = true;
      show(el.app, false);
      show(el.result, false);
      show(el.gate, false);
      document.body.textContent = '';
      var done = document.createElement('section');
      var heading = document.createElement('h1');
      heading.textContent = 'You can close this page.';
      done.appendChild(heading);
      var line = document.createElement('p');
      line.className = 'lede';
      line.textContent = 'Press Stop on the TV when you are finished.';
      done.appendChild(line);
      document.body.appendChild(done);
    });

    el.resultAgain.addEventListener('click', function () {
      draftId = null;
      operations = [];
      orderEdits = {};
      show(el.result, false);
      show(el.app, true);
      updateReview();
      loadState();
    });

    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') {
        if (pollTimer) {
          window.clearTimeout(pollTimer);
          pollTimer = null;
        }
        return;
      }
      if (pollActive) {
        pollDelay = 400;
        schedulePoll();
      }
    });

    window.addEventListener('pagehide', function () {
      stopPolling();
      stopCountdown();
    });
  }

  function probeSession() {
    return api('GET', '/api/session').then(function (result) {
      if (result.status !== 200 || !result.body) {
        if (result.status === 401 || result.status === 410) {
          sessionLost(result.status);
          return;
        }
        setError('The TV did not answer. Start a new session on the TV.');
        show(el.app, false);
        return;
      }
      remainingMs = Number(result.body.remainingMs) || 0;
      el.remaining.textContent = Math.max(1, Math.floor(remainingMs / 1000 / 60)) + ' min left';
      startCountdown();
    }).catch(function () {
      setError('Could not reach the TV. Check that it is on the same network.');
    });
  }

  function boot() {
    bind();
    token = readFragmentToken();
    if (token) {
      show(el.gate, false);
      show(el.app, true);
      probeSession();
      loadState();
      return;
    }

    show(el.app, false);
    show(el.gate, true);
    el.code.focus();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
