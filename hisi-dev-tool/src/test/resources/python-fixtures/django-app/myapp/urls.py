from django.urls import path
from . import views

urlpatterns = [
    path("users/", views.user_list),
    path("users/<int:user_id>/", views.user_detail),
    path("orders/", views.OrderView.as_view()),
]
