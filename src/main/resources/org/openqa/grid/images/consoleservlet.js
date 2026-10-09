(function() {
  "use strict";

  function show(proxy, section) {
    proxy.find('.tab').each(function() {
      $(this).toggleClass('selected', $(this).attr('type') === section);
    });

    proxy.find('.content_detail').each(function() {
      $(this).toggle($(this).attr('type') === section);
    });
  }

  function showDefaults() {
    $('.proxy').each(function() {
      show($(this), 'info');
    });
  }

  $(document).ready(function() {
    $('.tabs li').click(function(event) {
      show($(this).closest('.proxy'), $(this).attr('type'));
      event.preventDefault();
    });

    $('#config-view-toggle').click(function(event) {
      var configDetails = $('#hub-config-content');
      var hidden = configDetails.is(':visible');
      configDetails.toggle(!hidden);
      $(this).text(hidden ? 'View Config' : 'Hide Config');
      event.preventDefault();
    });

    $('#verbose-config-view-toggle').click(function(event) {
      $('#verbose-config-content').toggle();
      $(this).hide();
      event.preventDefault();
    });

    $('#header h2').text('MCloud-grid');
    document.title = 'MCloud-grid';
    showDefaults();
  });
}());

