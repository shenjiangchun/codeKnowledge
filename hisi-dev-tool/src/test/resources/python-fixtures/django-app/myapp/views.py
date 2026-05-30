from django.http import JsonResponse
from django.views import View
import requests
from . import utils


def user_list(request):
    return JsonResponse({"users": []})


def user_detail(request, user_id):
    data = requests.get("http://user-service/api/users/" + str(user_id))
    formatted = utils.format_user(data.json())
    return JsonResponse(formatted)


class OrderView(View):
    def get(self, request):
        return JsonResponse({"orders": []})

    def post(self, request):
        return JsonResponse({"created": True})
