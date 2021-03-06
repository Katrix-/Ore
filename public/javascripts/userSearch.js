/*
 * ==================================================
 *  _____             _
 * |     |___ ___    |_|___
 * |  |  |  _| -_|_  | |_ -|
 * |_____|_| |___|_|_| |___|
 *                 |___|
 *
 * By Walker Crouse (windy) and contributors
 * (C) SpongePowered 2016-2017 MIT License
 * https://github.com/SpongePowered/Ore
 *
 * Powers the asynchronous user lookups via AJAX used throughout the site.
 *
 * ==================================================
 */

var KEY_ENTER = 13;

/*
 * ==================================================
 * =                Helper functions                =
 * ==================================================
 */

function initUserSearch(callback) {
    var search = $('.user-search');
    var input = search.find('input');

    // Disable button with no input
    input.on('input', function() {
        $(this).next().find('.btn').prop('disabled', $(this).val().length == 0);
    });

    // Catch enter key
    input.on('keypress', function(event) {
        if (event.keyCode === KEY_ENTER) {
            event.preventDefault();
            $(this).next().find('.btn').click();
        }
    });

    // Search for user
    search.find('.btn-search').click(function() {
        var input = $(this).closest('.user-search').find('input');
        var username = input.val().trim();
        var icon = $(this).find('i').removeClass('fa-search').addClass('fa-spinner fa-spin');
        $.ajax({
            url: '/api/users/' + username,
            dataType: 'json',

            complete: function() {
                input.val('');
                icon.removeClass('fa-spinner fa-spin').addClass('fa-search').prop('disabled', true);
            },

            error: function() {
                callback({
                    isSuccess: false,
                    username: username,
                    user: null
                })
            },

            success: function(user) {
                callback({
                    isSuccess: true,
                    username: username,
                    user: user
                });
            }
        });
    });
}
