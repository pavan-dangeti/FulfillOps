/* global $ */
$(function () {
  'use strict';

  var $search = $('#search');

  function closeModal() {
    $('#refund-modal').removeClass('open');
  }

  function badgeStatus($row) {
    return $.trim($row.find('.status-cell').text());
  }

  function applyBadge(target, html) {
    var $row = target.row ? target.row : $('[data-row="' + target.orderNumber + '"]');
    $row.find('.status-cell').html(html);
    var status = badgeStatus($row);
    if (status === 'REFUNDED') {
      $('[data-row="' + target.orderNumber + '"] [data-action]').remove();
    } else if (status === 'SHIPPED') {
      $('[data-row="' + target.orderNumber + '"] [data-action="ship"]').remove();
    }
  }

  if ($search.length) {
    $search.on('input', function () {
      var q = $(this).val().toLowerCase();
      $('#orders-table tbody tr').each(function () {
        $(this).toggle($(this).text().toLowerCase().indexOf(q) !== -1);
      });
      $('#orders-table tbody tr').filter(':visible').length === 0
        ? $('.empty').show() : $('.empty').hide();
    });
  }

  var pending = null;

  $(document).on('click', '[data-action="refund"]', function () {
    var $btn = $(this);
    pending = {
      orderNumber: $btn.data('order'),
      row: $btn.closest('[data-row]')
    };
    $('#refund-modal').addClass('open');
  });

  $(document).on('click', '[data-action="ship"]', function () {
    var $btn = $(this);
    var target = { orderNumber: $btn.data('order'), row: $btn.closest('[data-row]') };
    $.post('/orders/' + target.orderNumber + '/ship').done(function (html) {
      applyBadge(target, html);
    });
  });

  $('#confirm-refund').on('click', function () {
    if (!pending) { return; }
    $.post('/orders/' + pending.orderNumber + '/refund')
      .done(function (html) {
        applyBadge(pending, html);
        pending = null;
        closeModal();
      });
  });

  $('#cancel-refund, #refund-modal').on('click', function (e) {
    if (e.target === this) { closeModal(); }
  });
});